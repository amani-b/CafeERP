/* ============================================================
   Cafe ERP — shared app behavior
   Loaded on every page via fragments/layout :: head.
   Framework-free (matches the rest of the app's vanilla-JS
   workarounds for the parts Franken UI v2.1.2 doesn't ship).
   ============================================================ */
(function () {
  'use strict';

  /* ---- Mobile off-canvas nav ------------------------------ */
  var mobileNavToggle = document.getElementById('mobile-nav-toggle');
  var mobileNavClose = document.getElementById('mobile-nav-close');
  var mobileNavOverlay = document.getElementById('mobile-nav-overlay');
  var mobileNavPanel = document.getElementById('mobile-nav-panel');

  if (mobileNavToggle && mobileNavPanel) {
    function openMobileNav() {
      mobileNavPanel.classList.add('open');
      if (mobileNavOverlay) mobileNavOverlay.classList.add('open');
      mobileNavToggle.setAttribute('aria-expanded', 'true');
      document.body.style.overflow = 'hidden';
    }

    function closeMobileNav() {
      mobileNavPanel.classList.remove('open');
      if (mobileNavOverlay) mobileNavOverlay.classList.remove('open');
      mobileNavToggle.setAttribute('aria-expanded', 'false');
      document.body.style.overflow = '';
    }

    if (mobileNavToggle) mobileNavToggle.addEventListener('click', openMobileNav);
    if (mobileNavClose) mobileNavClose.addEventListener('click', closeMobileNav);
    if (mobileNavOverlay) mobileNavOverlay.addEventListener('click', closeMobileNav);

    // Escape closes the drawer — keyboard-navigation requirement.
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && mobileNavPanel.classList.contains('open')) {
        closeMobileNav();
        if (mobileNavToggle) mobileNavToggle.focus();
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
