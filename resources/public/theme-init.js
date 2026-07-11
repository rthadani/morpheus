(function(){
  var saved = null;
  try { saved = localStorage.getItem('morpheus-theme'); } catch (e) {}
  if (saved === 'light' || saved === 'dark') {
    document.documentElement.setAttribute('data-theme', saved);
  }
  window.morpheusToggleTheme = function() {
    var d = document.documentElement;
    var prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches;
    var current = d.getAttribute('data-theme') || (prefersDark ? 'dark' : 'light');
    var next = current === 'dark' ? 'light' : 'dark';
    d.setAttribute('data-theme', next);
    try { localStorage.setItem('morpheus-theme', next); } catch (e) {}
  };
})();
