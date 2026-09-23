// Dashboard uses classic JSX scripts with intentionally shared public components.
// Babel's per-file object-rest exclusion arrays must NOT share that namespace:
// a later page's `_excluded = ['status']` used to corrupt Button's rest props.
(function () {
  'use strict';
  var sequence = 0;
  window.Babel.registerPlugin('jkxt-script-scope', function () {
    return {
      visitor: {
        Program: {
          exit: function (program) {
            var prefix = 'jkxtRestKeys' + (++sequence) + '_';
            program.scope.crawl();
            Object.keys(program.scope.bindings).forEach(function (name) {
              if (/^_excluded\d*$/.test(name)) program.scope.rename(name, prefix + name);
            });
          }
        }
      }
    };
  });
  // This script is deliberately last in the document: all JSX tags now exist,
  // and Babel's DOMContentLoaded compilation has not started yet.
  document.querySelectorAll('script[type="text/babel"][src]').forEach(function (script) {
    var plugins = (script.dataset.plugins || '').split(',').filter(Boolean);
    if (plugins.indexOf('jkxt-script-scope') === -1) plugins.push('jkxt-script-scope');
    script.dataset.plugins = plugins.join(',');
  });
})();
