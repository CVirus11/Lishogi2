package views
package html.swiss

import lila.api.Context
import lila.app.templating.Environment._
import lila.app.ui.ScalatagsTemplate._
import lila.common.String.html.richText
import lila.swiss.Swiss

import controllers.routes

object side {

  private val separator = " • "

  def apply(s: Swiss, chat: Boolean)(implicit ctx: Context) =
    frag(
      div(cls := "swiss__meta")(
        st.section(dataIcon := s.perfType.map(_.iconChar.toString))(
          div(
            p(
              s.clock.show,
              separator,
              views.html.game.bits.variantLink(s.variant, s.perfType),
              separator,
              if (s.settings.rated) trans.ratedTournament() else trans.casualTournament()
            ),
            p(
              span(cls := "swiss__meta__round")(s"${s.round}/${s.settings.nbRounds}"),
              " rounds",
              separator,
              a(href := routes.Page.notSupported)("Swiss"),
              (isGranted(_.ManageTournament) || (ctx.userId.has(s.createdBy) && !s.isFinished)) option frag(
                " ",
                a(href := routes.Page.notSupported, title := "Edit tournament")(iconTag("%"))
              )
            ),
            bits.showInterval(s)
          )
        ),
        s.settings.description map { d =>
          st.section(cls := "description")(richText(d))
        },
        s.looksLikePrize option views.html.tournament.bits.userPrizeDisclaimer,
        teamLink(s.teamId),
        separator,
        absClientDateTime(s.startsAt)
      ),
      chat option views.html.chat.frag
    )
}
