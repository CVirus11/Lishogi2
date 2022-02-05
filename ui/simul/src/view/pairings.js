var m = require('mithril');
var util = require('./util');
var status = require('game/status');

function miniPairing(ctrl) {
  return function (pairing) {
    var game = pairing.game;
    var player = pairing.player;
    var result =
      pairing.game.status >= status.ids.mate
        ? pairing.winnerColor === 'sente'
          ? '1-0'
          : pairing.winnerColor === 'gote'
          ? '0-1'
          : '½/½'
        : '*';
    return m(
      'a',
      {
        href: '/' + game.id + '/' + game.orient,
        class: ctrl.data.host.gameId === game.id ? 'host' : '',
      },
      [
        m(
          'span',
          {
            class: 'mini-board mini-board-' + game.id + ' parse-sfen' + ' variant-' + game.variant,
            'data-color': game.orient,
            'data-sfen': game.sfen,
            'data-lastmove': game.lastMove,
            config: function (el, isUpdate) {
              if (!isUpdate) lishogi.parseSfen($(el));
            },
          },
          m('div.cg-wrap')
        ),
        m('span.vstext', [
          m('span.vstext__pl', [util.playerVariant(ctrl, player).name, m('br'), result]),
          m('div.vstext__op', [player.name, m('br'), player.title ? player.title + ' ' : '', player.rating]),
        ]),
      ]
    );
  };
}

module.exports = function (ctrl) {
  return m('div.game-list.now-playing.box__pad', ctrl.data.pairings.map(miniPairing(ctrl)));
};
