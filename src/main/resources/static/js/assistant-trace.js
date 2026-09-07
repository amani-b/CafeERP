/**
 * Phase 5 — shared "what the AI is doing" trace component.
 *
 * ONE implementation used by every streamed assistant flow (chat / edit /
 * regenerate). While the assistant works,
 * each step streams in as a short human-readable line; when the final reply
 * arrives, the whole trace collapses into a small expandable "N steps"
 * affordance so it never permanently clutters the chat.
 *
 * Usage:
 *   var trace = AssistantTrace.create();
 *   messagesContainer.appendChild(trace.begin());  // returns the live container
 *   trace.start('Looking up order #482…');         // streams a new active step
 *   trace.fail('…')                                // optional, marks failure
 *   var collapsed = trace.collapse();              // on reply: swap for <details>
 *
 * Animations use the app.css tokens (--ease/--dur) and are disabled entirely
 * under prefers-reduced-motion.
 */
window.AssistantTrace = (function () {
    'use strict';

    var reducedMotion = window.matchMedia &&
        window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    function create() {
        var container = null;
        var entries = [];
        var active = null;

        function begin() {
            container = document.createElement('div');
            container.className = 'assistant-trace';
            container.setAttribute('aria-live', 'polite');
            return container;
        }

        function makeEntry(text) {
            var el = document.createElement('div');
            el.className = 'assistant-trace-entry is-active';
            var dot = document.createElement('span');
            dot.className = 'assistant-trace-dot';
            var label = document.createElement('span');
            label.className = 'assistant-trace-text';
            label.textContent = text; // textContent — never inject HTML
            el.appendChild(dot);
            el.appendChild(label);
            return el;
        }

        /** Closes the active entry with the given final state ('done'|'fail'). */
        function finishActive(state) {
            if (!active) return;
            active.el.classList.remove('is-active');
            active.el.classList.add(state === 'fail' ? 'is-failed' : 'is-done');
            active.el.setAttribute('data-state', state);
            active = null;
        }

        /** Streams a new step line; any previous active step is closed as done. */
        function start(text) {
            if (!container) begin();
            finishActive('done');
            var el = makeEntry(text);
            container.appendChild(el);
            if (reducedMotion) {
                el.classList.add('is-visible');
            } else {
                // Double rAF so the hidden state is painted first and the
                // appear transition actually plays.
                requestAnimationFrame(function () {
                    requestAnimationFrame(function () {
                        el.classList.add('is-visible');
                    });
                });
            }
            active = { el: el, state: 'active' };
            entries.push(active);
        }

        /** Marks the current step failed (stream continues or errors out). */
        function fail() {
            finishActive('fail');
        }

        /**
         * Collapses the live trace into a compact expandable affordance.
         * Returns the replacement element (or null if nothing was streamed).
         */
        function collapse() {
            finishActive('done');
            if (!container || !entries.length) return null;

            var details = document.createElement('details');
            details.className = 'assistant-trace-collapsed';
            var summary = document.createElement('summary');
            summary.textContent = entries.length + (entries.length === 1 ? ' step' : ' steps');
            details.appendChild(summary);

            var list = document.createElement('div');
            list.className = 'assistant-trace-list';
            entries.forEach(function (entry) {
                list.appendChild(entry.el);
            });
            details.appendChild(list);

            var wrapper = document.createElement('div');
            wrapper.className = 'assistant-trace is-collapsed';
            wrapper.appendChild(details);
            container.replaceWith(wrapper);

            container = null;
            entries = [];
            active = null;
            return wrapper;
        }

        function el() {
            return container;
        }

        return { begin: begin, start: start, fail: fail, collapse: collapse, el: el };
    }

    return { create: create };
})();
