package controllers

import play.api.libs.json._
import play.api.mvc._
import scala.concurrent.duration._

import lila.api.{ Context, BodyContext }
import lila.app._
import lila.app.mashup.{ GameFilterMenu, GameFilter }
import lila.common.paginator.Paginator
import lila.common.PimpedJson._
import lila.common.{ IpAddress, HTTPRequest }
import lila.game.{ GameRepo, Game => GameModel }
import lila.rating.PerfType
import lila.socket.UserLagCache
import lila.user.{ User => UserModel, UserRepo }
import views._

object User extends LilaController {

  private def env = Env.user
  private def relationApi = Env.relation.api
  private def userGameSearch = Env.gameSearch.userGameSearch

  def tv(username: String) = Open { implicit ctx =>
    OptionFuResult(UserRepo named username) { user =>
      (GameRepo lastPlayedPlaying user) orElse
        (GameRepo lastPlayed user) flatMap {
          _.fold(fuccess(Redirect(routes.User.show(username)))) { pov =>
            Round.watch(pov, userTv = user.some)
          }
        }
    }
  }

  def studyTv(username: String) = Open { implicit ctx =>
    OptionResult(UserRepo named username) { user =>
      Redirect {
        Env.relation.online.studying getIfPresent user.id match {
          case None => routes.Study.byOwnerDefault(user.id)
          case Some(studyId) => routes.Study.show(studyId)
        }
      }
    }
  }

  private def apiGames(u: UserModel, filter: String, page: Int)(implicit ctx: BodyContext[_]) = {
    userGames(u, filter, page) flatMap Env.api.userGameApi.jsPaginator map { res =>
      Ok(res ++ Json.obj("filter" -> GameFilter.All.name))
    }
  }.mon(_.http.response.user.show.mobile)

  def show(username: String) = OpenBody { implicit ctx =>
    EnabledUser(username) { u =>
      negotiate(
        html = renderShow(u),
        api = _ => apiGames(u, GameFilter.All.name, 1)
      )
    }
  }
  private def renderShow(u: UserModel, status: Results.Status = Results.Ok)(implicit ctx: Context) =
    if (HTTPRequest.isSynchronousHttp(ctx.req)) {
      for {
        as <- Env.activity.read.recent(u)
        nbs ← Env.current.userNbGames(u, ctx)
        info ← Env.current.userInfo(u, nbs, ctx)
        social ← Env.current.socialInfo(u, ctx)
      } yield status(html.user.show.activity(u, as, info, social))
    }.mon(_.http.response.user.show.website)
    else Env.activity.read.recent(u) map { as =>
      status(html.activity.list(u, as))
    }

  def gamesAll(username: String, page: Int) = games(username, GameFilter.All.name, page)

  def games(username: String, filter: String, page: Int) = OpenBody { implicit ctx =>
    Reasonable(page) {
      EnabledUser(username) { u =>
        negotiate(
          html = for {
            nbs ← Env.current.userNbGames(u, ctx)
            filters = GameFilterMenu(u, nbs, filter)
            pag <- GameFilterMenu.paginatorOf(
              userGameSearch = userGameSearch,
              user = u,
              nbs = nbs.some,
              filter = filters.current,
              me = ctx.me,
              page = page
            )(ctx.body)
            _ <- Env.user.lightUserApi preloadMany pag.currentPageResults.flatMap(_.userIds)
            _ <- Env.tournament.cached.nameCache preloadMany pag.currentPageResults.flatMap(_.tournamentId)
            res <- if (HTTPRequest.isSynchronousHttp(ctx.req)) for {
              info ← Env.current.userInfo(u, nbs, ctx)
              _ <- Env.team.cached.nameCache preloadMany info.teamIds
              social ← Env.current.socialInfo(u, ctx)
              searchForm = (filters.current == GameFilter.Search) option GameFilterMenu.searchForm(userGameSearch, filters.current)(ctx.body)
            } yield html.user.show.games(u, info, pag, filters, searchForm, social)
            else fuccess(html.user.show.gamesContent(u, nbs, pag, filters, filter))
          } yield res,
          api = _ => apiGames(u, filter, page)
        )
      }
    }
  }

  private def EnabledUser(username: String)(f: UserModel => Fu[Result])(implicit ctx: Context): Fu[Result] =
    OptionFuResult(UserRepo named username) { u =>
      if (u.enabled || isGranted(_.UserSpy)) f(u)
      else negotiate(
        html = fuccess(NotFound(html.user.disabled(u))),
        api = _ => fuccess(NotFound(jsonError("No such user, or account closed")))
      )
    }

  def showMini(username: String) = Open { implicit ctx =>
    OptionFuResult(UserRepo named username) { user =>
      if (user.enabled || isGranted(_.UserSpy)) for {
        blocked <- ctx.userId ?? { relationApi.fetchBlocks(user.id, _) }
        crosstable <- ctx.userId ?? { Env.game.crosstableApi(user.id, _) }
        followable <- ctx.isAuth ?? { Env.pref.api.followable(user.id) }
        relation <- ctx.userId ?? { relationApi.fetchRelation(_, user.id) }
        ping = env.isOnline(user.id) ?? UserLagCache.getLagRating(user.id)
        res <- negotiate(
          html = !ctx.is(user) ?? GameRepo.lastPlayedPlaying(user) map { pov =>
            Ok(html.user.mini(user, pov, blocked, followable, relation, ping, crosstable))
              .withHeaders(CACHE_CONTROL -> "max-age=5")
          },
          api = _ => {
            import lila.game.JsonView.crosstableWrites
            fuccess(Ok(Json.obj(
              "crosstable" -> crosstable,
              "perfs" -> lila.user.JsonView.perfs(user, user.best8Perfs)
            )))
          }
        )
      } yield res
      else fuccess(Ok(html.user.miniClosed(user)))
    }
  }

  def online = Open { implicit req =>
    val max = 50
    negotiate(
      html = notFound,
      api = _ => env.cached.getTop50Online map { list =>
        Ok(Json.toJson(list.take(getInt("nb").fold(10)(_ min max)).map(env.jsonView(_))))
      }
    )
  }

  private val UserGamesRateLimitPerIP = new lila.memo.RateLimit[IpAddress](
    credits = 500,
    duration = 10 minutes,
    name = "user games web/mobile per IP",
    key = "user_games.web.ip"
  )

  implicit val userGamesDefault =
    ornicar.scalalib.Zero.instance[Fu[Paginator[GameModel]]](fuccess(Paginator.empty[GameModel]))

  private def userGames(
    u: UserModel,
    filterName: String,
    page: Int
  )(implicit ctx: BodyContext[_]): Fu[Paginator[GameModel]] = {
    UserGamesRateLimitPerIP(HTTPRequest lastRemoteAddress ctx.req, cost = page, msg = s"on ${u.username}") {
      lila.mon.http.userGames.cost(page)
      GameFilterMenu.paginatorOf(
        userGameSearch = userGameSearch,
        user = u,
        nbs = none,
        filter = GameFilterMenu.currentOf(GameFilterMenu.all, filterName),
        me = ctx.me,
        page = page
      )(ctx.body)
    }
  }

  def list = Open { implicit ctx =>
    val nb = 10
    env.cached.leaderboards flatMap { leaderboards =>
      negotiate(
        html = for {
          nbAllTime ← env.cached topNbGame nb
          nbDay ← fuccess(Nil)
          tourneyWinners ← Env.tournament.winners.all.map(_.top)
          online ← env.cached.getTop50Online
          _ <- Env.user.lightUserApi preloadMany tourneyWinners.map(_.userId)
        } yield Ok(html.user.list(
          tourneyWinners = tourneyWinners,
          online = online,
          leaderboards = leaderboards,
          nbDay = nbDay,
          nbAllTime = nbAllTime
        )),
        api = _ => fuccess {
          implicit val lpWrites = OWrites[UserModel.LightPerf](env.jsonView.lightPerfIsOnline)
          Ok(Json.obj(
            "bullet" -> leaderboards.bullet,
            "blitz" -> leaderboards.blitz,
            "rapid" -> leaderboards.rapid,
            "classical" -> leaderboards.classical,
            "ultraBullet" -> leaderboards.ultraBullet,
            "crazyhouse" -> leaderboards.crazyhouse,
            "chess960" -> leaderboards.chess960,
            "kingOfTheHill" -> leaderboards.kingOfTheHill,
            "threeCheck" -> leaderboards.threeCheck,
            "antichess" -> leaderboards.antichess,
            "atomic" -> leaderboards.atomic,
            "horde" -> leaderboards.horde,
            "racingKings" -> leaderboards.racingKings
          ))
        }
      )
    }
  }

  def topNb(nb: Int, perfKey: String) = Open { implicit ctx =>
    PerfType(perfKey) ?? { perfType =>
      env.cached top200Perf perfType.id map { _ take (nb atLeast 1 atMost 200) } flatMap { users =>
        negotiate(
          html = Ok(html.user.top(perfType, users)).fuccess,
          api = _ => fuccess {
            implicit val lpWrites = OWrites[UserModel.LightPerf](env.jsonView.lightPerfIsOnline)
            Ok(Json.obj("users" -> users))
          }
        )
      }
    }
  }

  def topWeek = Open { implicit ctx =>
    negotiate(
      html = notFound,
      api = _ => env.cached.topWeek(()).map { users =>
        Ok(Json toJson users.map(env.jsonView.lightPerfIsOnline))
      }
    )
  }

  def mod(username: String) = Secure(_.UserSpy) { implicit ctx => me =>
    modZoneOrRedirect(username, me)
  }

  protected[controllers] def modZoneOrRedirect(username: String, me: UserModel)(implicit ctx: Context): Fu[Result] =
    if (HTTPRequest isSynchronousHttp ctx.req) fuccess(Mod.redirect(username))
    else if (Env.streamer.liveStreamApi.isStreaming(me.id)) fuccess(Ok("Disabled while streaming"))
    else renderModZone(username, me)

  protected[controllers] def renderModZone(username: String, me: UserModel)(implicit ctx: Context): Fu[Result] =
    OptionFuOk(UserRepo named username) { user =>
      UserRepo.emails(user.id) zip
        UserRepo.isErased(user) zip
        (Env.security userSpy user) zip
        Env.mod.assessApi.getPlayerAggregateAssessmentWithGames(user.id) zip
        Env.mod.logApi.userHistory(user.id) zip
        Env.plan.api.recentChargesOf(user) zip
        Env.report.api.byAndAbout(user, 20) zip
        Env.pref.api.getPref(user) zip
        Env.irwin.api.reports.withPovs(user) flatMap {
          case emails ~ erased ~ spy ~ assess ~ history ~ charges ~ reports ~ pref ~ irwin =>
            val familyUserIds = user.id :: spy.otherUserIds.toList
            Env.playban.api.bans(familyUserIds) zip
              Env.user.noteApi.forMod(familyUserIds) zip
              Env.user.lightUserApi.preloadMany {
                reports.userIds ::: assess.??(_.games).flatMap(_.userIds)
              } map {
                case bans ~ notes ~ _ =>
                  html.user.mod(user, emails, spy, assess, bans, history, charges, reports, pref, irwin, notes, erased)
              }
        }
    }

  def writeNote(username: String) = AuthBody { implicit ctx => me =>
    OptionFuResult(UserRepo named username) { user =>
      implicit val req = ctx.body
      env.forms.note.bindFromRequest.fold(
        err => renderShow(user, Results.BadRequest),
        data => env.noteApi.write(user, data.text, me, data.mod && isGranted(_.ModNote)) inject
          Redirect(routes.User.show(username).url + "?note")
      )
    }
  }

  def deleteNote(id: String) = Auth { implicit ctx => me =>
    OptionFuResult(env.noteApi.byId(id)) { note =>
      note.isFrom(me) ?? {
        env.noteApi.delete(note._id) inject Redirect(routes.User.show(note.to).url + "?note")
      }
    }
  }

  def opponents = Auth { implicit ctx => me =>
    for {
      ops <- Env.game.bestOpponents(me.id)
      followables <- Env.pref.api.followables(ops map (_._1.id))
      relateds <- ops.zip(followables).map {
        case ((u, nb), followable) => relationApi.fetchRelation(me.id, u.id) map {
          lila.relation.Related(u, nb.some, followable, _)
        }
      }.sequenceFu
    } yield html.user.opponents(me, relateds)
  }

  def perfStat(username: String, perfKey: String) = Open { implicit ctx =>
    OptionFuResult(UserRepo named username) { u =>
      if ((u.disabled || (u.lame && !ctx.is(u))) && !isGranted(_.UserSpy)) notFound
      else PerfType(perfKey).fold(notFound) { perfType =>
        for {
          perfStat <- Env.perfStat.get(u, perfType)
          ranks <- Env.user.cached.ranking.getAll(u.id)
          distribution <- u.perfs(perfType).established ?? {
            Env.user.cached.ratingDistribution(perfType) map some
          }
          _ <- Env.user.lightUserApi preloadMany { u.id :: perfStat.userIds.map(_.value) }
          data = Env.perfStat.jsonView(u, perfStat, ranks get perfType.key, distribution)
          response <- negotiate(
            html = Ok(html.user.perfStat(u, ranks, perfType, data)).fuccess,
            api = _ => getBool("graph").?? {
              Env.history.ratingChartApi.singlePerf(u, perfType).map(_.some)
            } map {
              _.fold(data) { graph => data + ("graph" -> graph) }
            } map { Ok(_) }
          )
        } yield response
      }
    }
  }

  def autocomplete = Open { implicit ctx =>
    get("term", ctx.req).filter(_.nonEmpty).filter(lila.user.User.couldBeUsername) match {
      case None => BadRequest("No search term provided").fuccess
      case Some(term) if getBool("exists") => UserRepo nameExists term map { r => Ok(JsBoolean(r)) }
      case Some(term) => {
        get("tour") match {
          case Some(tourId) => Env.tournament.playerRepo.searchPlayers(tourId, term, 10)
          case None => ctx.me.ifTrue(getBool("friend")) match {
            case None => UserRepo userIdsLike term
            case Some(follower) =>
              Env.relation.api.searchFollowedBy(follower, term, 10) flatMap {
                case Nil => UserRepo userIdsLike term
                case userIds => fuccess(userIds)
              }
          }
        }
      } flatMap { userIds =>
        if (getBool("object")) Env.user.lightUserApi.asyncMany(userIds) map { users =>
          Json.obj(
            "result" -> JsArray(users.flatten.map { u =>
              lila.common.LightUser.lightUserWrites.writes(u).add("online" -> Env.user.isOnline(u.id))
            })
          )
        }
        else fuccess(Json toJson userIds)
      } map { Ok(_) as JSON }
    }
  }

  def myself = Auth { ctx => me =>
    fuccess(Redirect(routes.User.show(me.username)))
  }
}
