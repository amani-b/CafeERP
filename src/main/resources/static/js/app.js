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

    // Close mobile nav when a link is clicked (smoothly)
    if (mobileNavPanel) {
      var links = mobileNavPanel.querySelectorAll('a');
      links.forEach(function (link) {
        link.addEventListener('click', function () {
          // Add a small delay to allow visual feedback before closing/navigation
          setTimeout(closeMobileNav, 150);
        });
      });
    }
  }

  /* ---- Scroll active nav link into view on page load --------
     Ensures the currently active page's nav item is visible
     and centered in the sidebar, especially useful for longer
     menus or when scrolling has occurred. */
  document.addEventListener('DOMContentLoaded', function () {
    var sidebar = document.querySelector('.app-sidebar nav');
    var activeLink = sidebar ? sidebar.querySelector('li.active a, li.active-page a') : null;
    
    if (sidebar && activeLink) {
      // Smooth scroll the active link into the center of the sidebar view
      activeLink.scrollIntoView({
        behavior: 'smooth',
        block: 'center',
        inline: 'nearest'
      });
    }
  });

  /* ---- Password visibility toggle --------------------------- */
  function initPasswordToggle(container) {
    var scope = container || document;
    var toggleButtons = scope.querySelectorAll('.toggle-password-btn, #toggle-login-password');
    
    toggleButtons.forEach(function(btn) {
      btn.addEventListener('click', function() {
        var wrapper = btn.closest('.password-input-wrapper');
        if (!wrapper) return;
        
        var input = wrapper.querySelector('input[type="password"], input[type="text"]');
        if (!input) return;
        
        var eyeIcon = btn.querySelector('.eye-icon');
        var eyeOffIcon = btn.querySelector('.eye-off-icon');
        
        if (input.type === 'password') {
          input.type = 'text';
          if (eyeIcon) eyeIcon.style.display = 'none';
          if (eyeOffIcon) eyeOffIcon.style.display = 'inline';
        } else {
          input.type = 'password';
          if (eyeIcon) eyeIcon.style.display = 'inline';
          if (eyeOffIcon) eyeOffIcon.style.display = 'none';
        }
      });
    });
  }
  
  // Initialize on page load
  document.addEventListener('DOMContentLoaded', function() {
    initPasswordToggle();
  });

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

  /* ---- Deploy self-announcement ("updates available") banner ----
     Compares the build version stamped into the page at build time
     (<meta name="build-version">) against what the server currently
     reports (GET /build-version). On a mismatch, shows a small
     non-intrusive banner offering a refresh — NEVER auto-refreshes. */
  (function () {
    var CHECK_INTERVAL_MS = 60000;

    function pageVersion() {
      var meta = document.querySelector('meta[name="build-version"]');
      return meta ? meta.getAttribute('content') : null;
    }

    function showBanner() {
      if (document.getElementById('build-update-banner')) return;
      var banner = document.createElement('div');
      banner.id = 'build-update-banner';
      banner.setAttribute('role', 'status');
      banner.innerHTML =
        '<span class="build-update-text">Updates available — refresh to see the latest changes.</span>' +
        '<button type="button" class="build-update-btn">Refresh</button>' +
        '<button type="button" class="build-update-dismiss" aria-label="Dismiss">&times;</button>';
      banner.querySelector('.build-update-btn').addEventListener('click', function () {
        window.location.reload();
      });
      banner.querySelector('.build-update-dismiss').addEventListener('click', function () {
        banner.remove();
      });
      document.body.appendChild(banner);
    }

    function check() {
      var current = pageVersion();
      if (!current) return; // page without the stamp — nothing to compare
      fetch('/build-version', { cache: 'no-store' })
        .then(function (r) { return r.ok ? r.text() : null; })
        .then(function (serverVersion) {
          if (serverVersion && serverVersion.trim() !== current) showBanner();
        })
        .catch(function () { /* network hiccup — try again next tick */ });
    }

    function start() {
      check();
      setInterval(check, CHECK_INTERVAL_MS);
      // Also check on next navigation/interaction (tab becomes visible).
      document.addEventListener('visibilitychange', function () {
        if (!document.hidden) check();
      });
      window.addEventListener('focus', check);
    }

    if (document.readyState === 'loading') {
      document.addEventListener('DOMContentLoaded', start);
    } else {
      start();
    }
  })();
})();
