/* ============================================================
   Cafe ERP — shared app behavior
   Loaded on every page via fragments/layout :: head.
   Framework-free (matches the rest of the app's vanilla-JS
   workarounds for the parts Franken UI v2.1.2 doesn't ship).
   ============================================================ */
(function () {
  'use strict';

  /* ---- Mobile off-canvas nav ------------------------------ */
  var drawer = document.getElementById('mobile-nav');
  if (drawer) {
    var toggle = document.getElementById('mobile-nav-toggle');
    var closeBtn = document.getElementById('mobile-nav-close');

    function openDrawer() {
      drawer.classList.add('uk-open');
      if (toggle) toggle.setAttribute('aria-expanded', 'true');
    }
    function closeDrawer() {
      drawer.classList.remove('uk-open');
      if (toggle) toggle.setAttribute('aria-expanded', 'false');
    }

    if (toggle) toggle.addEventListener('click', openDrawer);
    if (closeBtn) closeBtn.addEventListener('click', closeDrawer);

    // Click on the overlay (outside the drawer panel) closes it.
    drawer.addEventListener('click', function (e) {
      if (drawer.classList.contains('uk-open') && !e.target.closest('.uk-offcanvas-bar')) {
        closeDrawer();
      }
    });

    // Escape closes the drawer — keyboard-navigation requirement.
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && drawer.classList.contains('uk-open')) {
        closeDrawer();
        if (toggle) toggle.focus();
      }
    });
  }

  /* ---- Submit-button loading state -------------------------
     Prevents accidental double-submits (e.g. tapping "Create
     Order" twice on a slow connection) and gives visible
     feedback that the click registered. Skips forms explicitly
     opted out with data-no-loading (none currently, but kept as
     an escape hatch). */
  document.querySelectorAll('form:not([data-no-loading])').forEach(function (form) {
    form.addEventListener('submit', function () {
      if (form.hasAttribute('data-invalid')) return;
      var btn = form.querySelector('button[type="submit"]');
      if (!btn || btn.hasAttribute('data-loading')) return;
      btn.setAttribute('data-loading', 'true');
    });
  });

  /* ---- uk-alert close buttons -------------------------------
     Franken UI v2.1.2's core.iife.js does not register a click
     handler for [uk-close] the way earlier UIkit versions did,
     so alerts with a manual close button need one wired here. */
  document.querySelectorAll('[uk-close]').forEach(function (btn) {
    btn.addEventListener('click', function () {
      var alertEl = btn.closest('[uk-alert], .alert');
      if (alertEl) alertEl.remove();
    });
  });
})();
