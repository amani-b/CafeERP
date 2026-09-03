(function () {
    'use strict';

    var widget = document.getElementById('assistant-chat-widget');
    var messagesContainer = document.getElementById('assistant-chat-messages');
    var inputEl = document.getElementById('assistant-chat-input');
    var sendBtn = document.getElementById('assistant-chat-send');
    var panel = document.getElementById('assistant-chat-panel');
    var bubble = document.getElementById('assistant-chat-bubble');

    if (!widget || !messagesContainer || !inputEl || !sendBtn || !panel || !bubble) {
        return; // widget not rendered on this page
    }

    var csrfToken = widget.getAttribute('data-csrf-token');
    var prefersReducedMotion = window.matchMedia &&
        window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    // ------------------------------ state ------------------------------
    var panelOpen = false;
    var fullscreen = false;      // OFF by default — user-triggered only
    var sidebarCollapsed = false; // fullscreen sidebar toggle (mobile-friendly)
    var overlayOpen = false;     // compact-view history slide-in
    var currentConversationId = null;
    var sendInFlight = false;    // guards the history-render race
    var pendingHistoryRefresh = false;
    var pendingEditOriginal = null; // set while the user is editing a previous query

    // Shared history-list mount points: the fullscreen sidebar and the
    // compact overlay. ONE renderer feeds both — no duplicated logic.
    var historyMounts = [
        { list: document.getElementById('assistant-chat-sidebar-list'),
          archivedToggle: document.getElementById('assistant-chat-archived-sidebar') },
        { list: document.getElementById('assistant-chat-overlay-list'),
          archivedToggle: document.getElementById('assistant-chat-archived-overlay') }
    ];

    function csrfHeaders(extra) {
        var h = extra || {};
        if (csrfToken) h['X-CSRF-TOKEN'] = csrfToken;
        return h;
    }

    function escapeHtml(text) {
        var div = document.createElement('div');
        div.appendChild(document.createTextNode(text));
        return div.innerHTML;
    }

    // Collapse runs of 2+ blank lines that some models emit despite prompt
    // instructions — belt-and-braces alongside the tightened CSS rhythm.
    function normalizeMarkdownWhitespace(text) {
        return String(text).replace(/\n{3,}/g, '\n\n').trim();
    }

    var ICONS = {
        resend: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 14 4 9 9 4"></polyline><path d="M20 20v-7a4 4 0 0 0-4-4H4"></path></svg>',
        copy: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path></svg>',
        copyOk: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="20 6 9 17 4 12"></polyline></svg>',
        edit: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"></path><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"></path></svg>',
        regenerate: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="1 4 1 10 7 10"></polyline><polyline points="23 20 23 14 17 14"></polyline><path d="M20.49 9A9 9 0 0 0 5.64 5.64L1 10m22 4l-4.64 4.36A9 9 0 0 1 3.51 15"></path></svg>'
    };

    function makeIconButton(kind, label) {
        var btn = document.createElement('button');
        btn.type = 'button';
        btn.className = 'msg-action-btn msg-action-' + kind;
        btn.title = label;
        btn.setAttribute('aria-label', label);
        btn.innerHTML = ICONS[kind];
        return btn;
    }

    function copyTextToClipboard(text, btn) {
        var done = function () {
            if (!btn) return;
            var prev = btn.innerHTML;
            btn.innerHTML = ICONS.copyOk;
            btn.classList.add('copied');
            setTimeout(function () {
                btn.innerHTML = prev;
                btn.classList.remove('copied');
            }, 1200);
        };
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text).then(done, done);
        } else {
            done();
        }
    }

    function buildMessageShell(role) {
        var div = document.createElement('div');
        div.className = 'assistant-chat-msg ' + role;
        div.setAttribute('data-role', role);

        var roleLabel = document.createElement('div');
        roleLabel.className = 'msg-role';
        roleLabel.textContent = role === 'user' ? 'You' : 'Assistant';
        div.appendChild(roleLabel);

        var textDiv = document.createElement('div');
        textDiv.className = 'msg-text';
        div.appendChild(textDiv);

        // Per-message actions. User queries: resend / copy / edit — hover-only.
        // Assistant replies: copy / regenerate — always visible.
        var actions = document.createElement('div');
        actions.className = 'msg-actions';
        var actionsInner = document.createElement('div');
        actionsInner.className = 'msg-actions-inner';
        actions.appendChild(actionsInner);
        if (role === 'user') {
            var resend = makeIconButton('resend', 'Resend this message');
            resend.addEventListener('click', function () {
                if (sendInFlight) return;
                inputEl.value = textDiv.textContent;
                inputEl.style.height = Math.min(inputEl.scrollHeight, 110) + 'px';
                sendMessage();
            });
            var copyU = makeIconButton('copy', 'Copy');
            copyU.addEventListener('click', function () {
                copyTextToClipboard(textDiv.textContent, copyU);
            });
            var edit = makeIconButton('edit', 'Edit message');
            edit.addEventListener('click', function () {
                pendingEditOriginal = textDiv.textContent;
                inputEl.value = pendingEditOriginal;
                inputEl.style.height = 'auto';
                inputEl.style.height = Math.min(inputEl.scrollHeight, 110) + 'px';
                inputEl.classList.add('editing');
                inputEl.placeholder = 'Editing your message\u2026 (Esc to cancel)';
                inputEl.focus();
            });
            actionsInner.appendChild(resend);
            actionsInner.appendChild(copyU);
            actionsInner.appendChild(edit);

            // Hover-driven reveal: the actions drop below the bubble with a
            // slide animation (no space is reserved for them beforehand) and
            // collapse away again 10 seconds after the hover started — as if
            // they were never there.
            var hideTimer = null;
            div.addEventListener('mouseenter', function () {
                if (hideTimer) clearTimeout(hideTimer);
                div.classList.add('actions-open');
                hideTimer = setTimeout(function () {
                    div.classList.remove('actions-open');
                    hideTimer = null;
                }, 10000);
            });
            // Touch: tap the bubble toggles the row with the same 10s window.
            div.addEventListener('click', function () {
                if (div.classList.contains('actions-open')) return;
                if (hideTimer) clearTimeout(hideTimer);
                div.classList.add('actions-open');
                hideTimer = setTimeout(function () {
                    div.classList.remove('actions-open');
                    hideTimer = null;
                }, 10000);
            });
        } else {
            var copyA = makeIconButton('copy', 'Copy');
            copyA.addEventListener('click', function () {
                copyTextToClipboard(textDiv.textContent, copyA);
            });
            var regen = makeIconButton('regenerate', 'Regenerate response');
            regen.addEventListener('click', function () { openRegeneratePanel(div); });
            actionsInner.appendChild(copyA);
            actionsInner.appendChild(regen);
            // Assistant actions are permanently expanded.
            actions.classList.add('actions-open', 'always');
        }
        div.appendChild(actions);

        messagesContainer.appendChild(div);
        messagesContainer.scrollTop = messagesContainer.scrollHeight;
        return { root: div, textEl: textDiv };
    }

    // The original query a given assistant message answers: the nearest
    // preceding user bubble's text (DOM walk — works for both server-rendered
    // threads and freshly added messages).
    function originalQueryForAssistantMessage(root) {
        var prev = root.previousElementSibling;
        while (prev) {
            if (prev.classList && prev.classList.contains('assistant-chat-msg') &&
                prev.getAttribute('data-role') === 'user') {
                var t = prev.querySelector('.msg-text');
                return t ? t.textContent.trim() : '';
            }
            prev = prev.previousElementSibling;
        }
        return '';
    }

    // Regeneration prompt: "want to add feedback?" — optional. Both buttons
    // fire the regenerate request; the difference is only whether feedback
    // accompanies it.
    function openRegeneratePanel(assistantRoot) {
        if (sendInFlight) return;
        var existing = messagesContainer.querySelector('.msg-regen-panel');
        if (existing) existing.remove();

        var query = originalQueryForAssistantMessage(assistantRoot);
        if (!query) return;

        var panel = document.createElement('div');
        panel.className = 'msg-regen-panel';

        var label = document.createElement('div');
        label.className = 'msg-regen-label';
        label.textContent = 'Want to add feedback? (optional)';
        panel.appendChild(label);

        var input = document.createElement('input');
        input.type = 'text';
        input.className = 'msg-regen-input';
        input.placeholder = 'e.g. make it shorter, focus on sales\u2026';
        input.setAttribute('aria-label', 'Feedback for the regenerated response');
        panel.appendChild(input);

        var row = document.createElement('div');
        row.className = 'msg-regen-row';
        var go = document.createElement('button');
        go.type = 'button';
        go.className = 'msg-regen-go';
        go.textContent = 'Regenerate';
        var skip = document.createElement('button');
        skip.type = 'button';
        skip.className = 'msg-regen-skip';
        skip.textContent = 'No, just regenerate';
        row.appendChild(skip);
        row.appendChild(go);
        panel.appendChild(row);

        function fire(feedback) {
            panel.remove();
            regenerateResponse(query, feedback);
        }
        go.addEventListener('click', function () { fire(input.value.trim()); });
        skip.addEventListener('click', function () { fire(''); });
        input.addEventListener('keydown', function (e) {
            if (e.key === 'Enter') { e.preventDefault(); fire(input.value.trim()); }
            if (e.key === 'Escape') { e.stopPropagation(); panel.remove(); }
        });

        messagesContainer.insertBefore(panel, assistantRoot.nextSibling);
        input.focus();
        messagesContainer.scrollTop = messagesContainer.scrollHeight;
    }

    function regenerateResponse(query, feedback) {
        if (sendInFlight || !query) return;
        sendInFlight = true;
        setLoading(true);

        var body = { message: query, mode: 'regenerate' };
        if (feedback) body.feedback = feedback;
        if (currentConversationId) body.conversationId = currentConversationId;
        var attemptKey = (currentConversationId || '') + '|' + query;

        streamTurn(body, function (reply) {
                setLoading(false);
                sendInFlight = false;
                if (reply.conversationId) currentConversationId = reply.conversationId;
                regenAttempts[attemptKey] = (regenAttempts[attemptKey] || 1) + 1;
                addAssistantMessageWithReveal(reply.text, reply.links || []);
                if (reply.pendingActions && reply.pendingActions.length) {
                    reply.pendingActions.forEach(renderPendingActionCard);
                }
                refreshGenerationBadges();
                loadConversationMessages();
                refreshHistoryLists();
            }, function () {
                setLoading(false);
                sendInFlight = false;
                showError('Failed to regenerate a response. Please try again.');
                refreshHistoryLists();
            });
    }

    // Regeneration attempt counters, keyed per conversation+query: the 2nd
    // generation of an answer is labelled 2/2, the 3rd 3/3, and so on. Since
    // regeneration now REPLACES the previous AI reply (it is deleted), the
    // count is tracked here rather than by counting sibling replies.
    var regenAttempts = {};

    // Regeneration tracker: label each assistant reply whose generation count
    // exceeds one with "N/N". Recomputed from the DOM so it survives thread
    // re-renders from server history.
    function refreshGenerationBadges() {
        var msgs = messagesContainer.querySelectorAll('.assistant-chat-msg[data-role="assistant"]');
        for (var i = 0; i < msgs.length; i++) {
            var el = msgs[i];
            var existing = el.querySelector('.msg-gen');
            if (existing) existing.remove();
            var query = originalQueryForAssistantMessage(el);
            var n = regenAttempts[(currentConversationId || '') + '|' + query] || 1;
            if (n > 1) {
                var badge = document.createElement('div');
                badge.className = 'msg-gen';
                badge.textContent = n + '/' + n;
                el.appendChild(badge);
            }
        }
    }

    function renderAssistantMarkdown(el, text) {
        el.innerHTML = DOMPurify.sanitize(marked.parse(normalizeMarkdownWhitespace(text)));
    }

    // Cosmetic streaming reveal: types plain text quickly (total duration
    // capped), then swaps in the fully-rendered markdown. Skipped entirely
    // under prefers-reduced-motion.
    function addAssistantMessageWithReveal(text, links) {
        if (prefersReducedMotion || text.length < 24) {
            addMessage('assistant', text, links);
            return;
        }
        var shell = buildMessageShell('assistant');
        var el = shell.textEl;
        el.classList.add('is-revealing');

        var totalMs = Math.min(900, 300 + text.length); // fast regardless of length
        var startTime = null;
        var pendingLinks = links || [];

        function step(ts) {
            if (!startTime) startTime = ts;
            var progress = Math.min(1, (ts - startTime) / totalMs);
            var chars = Math.floor(text.length * progress);
            el.textContent = text.slice(0, chars);
            messagesContainer.scrollTop = messagesContainer.scrollHeight;
            if (progress < 1) {
                requestAnimationFrame(step);
            } else {
                el.classList.remove('is-revealing');
                renderAssistantMarkdown(el, text);
                if (pendingLinks.length > 0) appendLinks(shell.root, pendingLinks);
                messagesContainer.scrollTop = messagesContainer.scrollHeight;
            }
        }
        requestAnimationFrame(step);
    }

    function appendLinks(root, links) {
        var linksDiv = document.createElement('div');
        linksDiv.className = 'msg-links';
        links.forEach(function (link) {
            var a = document.createElement('a');
            a.href = link.url;
            a.textContent = link.label;
            linksDiv.appendChild(a);
        });
        root.appendChild(linksDiv);
    }

    function addMessage(role, text, links) {
        var shell = buildMessageShell(role);
        if (role === 'assistant') {
            renderAssistantMarkdown(shell.textEl, text);
        } else {
            shell.textEl.textContent = text;
        }
        if (links && links.length > 0) appendLinks(shell.root, links);
    }

    var loadingEl = null;
    function setLoading(loading) {
        if (loading) {
            if (loadingEl) return;
            loadingEl = document.createElement('div');
            loadingEl.className = 'assistant-chat-loading';
            var dots = document.createElement('div');
            dots.className = 'assistant-chat-loading-dots';
            for (var i = 0; i < 3; i++) {
                dots.appendChild(document.createElement('span'));
            }
            loadingEl.appendChild(dots);

            var label = document.createElement('span');
            label.className = 'assistant-chat-loading-label';
            label.textContent = 'Thinking\u2026';
            loadingEl.appendChild(label);

            messagesContainer.appendChild(loadingEl);
            messagesContainer.scrollTop = messagesContainer.scrollHeight;
            sendBtn.disabled = true;
            inputEl.disabled = true;
        } else {
            if (loadingEl && loadingEl.parentNode) loadingEl.parentNode.removeChild(loadingEl);
            loadingEl = null;
            sendBtn.disabled = false;
            inputEl.disabled = false;
        }
    }

    function showError(msg) {
        var el = document.createElement('div');
        el.className = 'assistant-chat-error';
        el.textContent = msg;
        messagesContainer.appendChild(el);
        messagesContainer.scrollTop = messagesContainer.scrollHeight;
    }

    // ------------------------- history lists -------------------------
    // ONE renderer shared by the fullscreen sidebar and the compact overlay.

    function renderHistoryList(container, conversations, activeId) {
        container.innerHTML = '';
        if (!conversations || conversations.length === 0) {
            var empty = document.createElement('div');
            empty.className = 'assistant-history-empty';
            empty.textContent = 'No conversations yet.';
            container.appendChild(empty);
            return;
        }
        conversations.forEach(function (c) {
            var item = document.createElement('button');
            item.type = 'button';
            item.className = 'assistant-history-item' + (String(c.id) === String(activeId) ? ' active' : '');
            item.setAttribute('data-conversation-id', c.id);
            item.setAttribute('data-testid', 'assistant-history-item');
            var title = c.title || 'New chat';
            item.textContent = title;
            if (c.archived) {
                var badge = document.createElement('span');
                badge.className = 'assistant-history-archived-badge';
                badge.textContent = 'Archived';
                item.appendChild(badge);
            }
            item.addEventListener('click', function () {
                switchConversation(c.id);
                if (overlayOpen) closeHistoryOverlay();
                // On narrow screens, collapse the fullscreen sidebar after
                // picking a thread so the conversation gets the full width.
                if (fullscreen && isNarrowViewport() && !sidebarCollapsed) {
                    setSidebarVisible(false);
                }
            });
            container.appendChild(item);
        });
    }

    function refreshHistoryLists() {
        historyMounts.forEach(function (mount) {
            var archived = mount.archivedToggle && mount.archivedToggle.checked;
            fetch('/assistant/conversations' + (archived ? '?archived=true' : ''))
                .then(function (r) {
                    if (!r.ok) throw new Error('history list failed');
                    return r.json();
                })
                .then(function (conversations) {
                    renderHistoryList(mount.list, conversations, currentConversationId);
                })
                .catch(function () {
                    mount.list.innerHTML = '';
                });
        });
    }

    historyMounts.forEach(function (mount) {
        if (mount.archivedToggle) {
            mount.archivedToggle.addEventListener('change', refreshHistoryLists);
        }
    });

    // ------------------------- thread rendering ----------------------
    // The ONLY place that replaces the message pane's content. Refusing to
    // re-render while a send is in flight is what fixes the old bug where an
    // in-flight history load wiped the message the user had just sent.

    function renderThread(messages) {
        messagesContainer.innerHTML = '';
        messages.forEach(function (m) {
            addMessage(m.role === 'USER' ? 'user' : 'assistant', m.content, null);
        });
        refreshGenerationBadges();
    }

    function loadConversationMessages() {
        if (!currentConversationId) {
            renderThread([]);
            return;
        }
        fetch('/assistant/conversations/' + currentConversationId + '/messages')
            .then(function (r) {
                if (!r.ok) throw new Error('thread load failed');
                return r.json();
            })
            .then(function (messages) {
                if (sendInFlight) {
                    pendingHistoryRefresh = true; // render AFTER the send settles
                    return;
                }
                renderThread(messages);
                // Pending AI actions are NOT part of the persisted message
                // history, so the confirmation cards must be (re-)rendered
                // from the server after every thread render — otherwise a
                // reload (including the one right after proposing an action)
                // wipes the card before the user can press Confirm/Cancel.
                renderPendingActionsForThread();
            })
            .catch(function () {
                if (!sendInFlight) showError('Could not load this conversation.');
            });
    }

    // ------------------------- conversations -------------------------

    function switchConversation(conversationId) {
        if (sendInFlight) return;
        currentConversationId = conversationId;
        loadConversationMessages();
        refreshHistoryLists();
        inputEl.focus();
    }

    function startNewChat() {
        if (sendInFlight) return;
        // Current thread already empty? Just reuse it — no empty-thread spam.
        if (!currentConversationId && messagesContainer.children.length === 0) {
            inputEl.focus();
            return;
        }
        fetch('/assistant/conversations', {
            method: 'POST',
            headers: csrfHeaders({ 'Content-Type': 'application/json' }),
            body: '{}'
        })
            .then(function (r) {
                if (!r.ok) throw new Error('new chat failed');
                return r.json();
            })
            .then(function (conversation) {
                currentConversationId = conversation.id;
                renderThread([]);
                refreshHistoryLists();
                inputEl.focus();
            })
            .catch(function () {
                showError('Could not start a new chat.');
            });
    }

    // ----------------------------- sending ---------------------------

    // ----- Phase 5: shared SSE streaming turn (one implementation for
    // chat / edit / regenerate; the Phase 6 coding tool reuses it too) -----

    // Appends a live trace container above the loading dots (or at the end
    // of the stream when the dots are not showing).
    function mountTrace() {
        var trace = AssistantTrace.create();
        var el = trace.begin();
        if (loadingEl) {
            messagesContainer.insertBefore(el, loadingEl);
        } else {
            messagesContainer.appendChild(el);
        }
        messagesContainer.scrollTop = messagesContainer.scrollHeight;
        return trace;
    }

    // Parses an SSE response body, invoking onEvent(name, dataString) per event.
    function consumeSse(response, onEvent) {
        var reader = response.body.getReader();
        var decoder = new TextDecoder();
        var buffer = '';

        function processBlock(block) {
            var eventName = 'message';
            var dataLines = [];
            block.split('\n').forEach(function (line) {
                if (line.indexOf('event:') === 0) {
                    eventName = line.slice(6).trim();
                } else if (line.indexOf('data:') === 0) {
                    dataLines.push(line.slice(5).trim());
                }
            });
            if (dataLines.length) onEvent(eventName, dataLines.join('\n'));
        }

        function pump() {
            return reader.read().then(function (chunk) {
                if (chunk.done) {
                    if (buffer.trim()) processBlock(buffer);
                    return;
                }
                buffer += decoder.decode(chunk.value, { stream: true });
                var idx;
                while ((idx = buffer.indexOf('\n\n')) !== -1) {
                    processBlock(buffer.slice(0, idx));
                    buffer = buffer.slice(idx + 2);
                }
                return pump();
            });
        }
        return pump();
    }

    // Runs one assistant turn against the streaming endpoint, driving a live
    // trace; falls back to the legacy JSON endpoints if streaming fails.
    // onReply(reply) handles the final AssistantReply; onFail() handles errors.
    function streamTurn(payload, onReply, onFail) {
        var trace = mountTrace();

        var finishOk = function (reply) {
            trace.collapse();
            onReply(reply);
        };
        var finishBad = function () {
            trace.fail();
            trace.collapse();
            onFail();
        };

        fetch('/assistant/chat/stream', {
            method: 'POST',
            headers: csrfHeaders({ 'Content-Type': 'application/json', 'Accept': 'text/event-stream' }),
            body: JSON.stringify(payload)
        })
            .then(function (r) {
                if (!r.ok || !r.body || !window.TextDecoder) {
                    throw new Error('stream unavailable');
                }
                return consumeSse(r, function (eventName, data) {
                    if (eventName === 'step') {
                        try {
                            var step = JSON.parse(data);
                            if (step && step.text) trace.start(step.text);
                        } catch (e) { /* malformed step — ignore */ }
                    } else if (eventName === 'reply') {
                        finishOk(JSON.parse(data));
                    }
                });
            })
            .catch(function () {
                // Legacy fallback: same turn against the non-streaming
                // endpoints, so the turn is never lost to a transport issue.
                var legacyPayload = {};
                Object.keys(payload).forEach(function (k) {
                    if (k !== 'mode') legacyPayload[k] = payload[k];
                });
                var endpoint = payload.mode === 'edit' ? '/assistant/edit'
                    : payload.mode === 'regenerate' ? '/assistant/regenerate'
                    : '/assistant/chat';
                fetch(endpoint, {
                    method: 'POST',
                    headers: csrfHeaders({ 'Content-Type': 'application/json' }),
                    body: JSON.stringify(legacyPayload)
                })
                    .then(function (r) {
                        if (!r.ok) throw new Error('Request failed');
                        return r.json();
                    })
                    .then(finishOk)
                    .catch(finishBad);
            });
    }

    function sendMessage() {
        var text = inputEl.value.trim();
        if (!text || sendInFlight) return;

        sendInFlight = true;
        inputEl.value = '';
        inputEl.style.height = 'auto';
        addMessage('user', text, null);
        setLoading(true);

    // The server resolves/creates the thread itself when no conversationId
    // is given (and returns the id it landed in) — no pre-flight
    // "create conversation" round-trip, which used to fail silently on a
    // stale/missing CSRF token and drop the turn before it was logged.
    // When editing, the turn goes to /assistant/edit instead: the original
    // query AND the replies it produced are deleted server-side and the
    // edited text is serviced as a fresh turn.
    var editing = !!pendingEditOriginal;
    var payload = editing
        ? { message: text, originalMessage: pendingEditOriginal, mode: 'edit' }
        : { message: text, mode: 'chat' };
    if (currentConversationId) payload.conversationId = currentConversationId;
    pendingEditOriginal = null; // consumed — Escape/normal send reset the mode
    inputEl.classList.remove('editing');
    inputEl.placeholder = 'Ask a question...';

    streamTurn(payload, function (reply) {
            setLoading(false);
            sendInFlight = false;
            // The server tells us which thread the turn persisted into.
            if (reply.conversationId) currentConversationId = reply.conversationId;
            addAssistantMessageWithReveal(reply.text, reply.links || []);
            if (reply.pendingActions && reply.pendingActions.length) {
                reply.pendingActions.forEach(renderPendingActionCard);
            }
            refreshGenerationBadges();
            // The turn is now durably persisted (server writes both sides
            // synchronously before responding) — re-sync the thread from
            // the server so the pane always matches the durable history,
            // and refresh the sidebar/overlay lists (title derived).
            loadConversationMessages();
            refreshHistoryLists();
        }, function () {
            setLoading(false);
            sendInFlight = false;
            showError('Failed to get a response. Please try again.');
            refreshHistoryLists();
        });
    }

    // -------------------- agentic actions (Phase 4) --------------------

    // Confirmation card: the AI proposed a write action that needs explicit
    // user approval. Renders tool, human-readable description and the raw
    // parameters, with Confirm / Cancel buttons wired to the audit-backed
    // endpoints. Nothing executes until Confirm is pressed.
    function renderPendingActionCard(action) {
        // Idempotent: a card for this action may already be on screen (it is
        // re-rendered from the server after every thread reload).
        if (messagesContainer.querySelector('[data-action-id="' + action.id + '"]')) {
            return;
        }
        var card = document.createElement('div');
        card.className = 'assistant-action-card';
        card.setAttribute('data-testid', 'assistant-action-card');
        card.setAttribute('data-action-id', action.id);

        var head = document.createElement('div');
        head.className = 'assistant-action-head';
        var title = document.createElement('span');
        title.className = 'assistant-action-title';
        title.textContent = 'Action needs your approval';
        var tool = document.createElement('code');
        tool.className = 'assistant-action-tool';
        tool.textContent = action.tool;
        head.appendChild(title);
        head.appendChild(tool);
        card.appendChild(head);

        var desc = document.createElement('div');
        desc.className = 'assistant-action-desc';
        desc.textContent = action.description;
        card.appendChild(desc);

        var params = document.createElement('pre');
        params.className = 'assistant-action-params';
        try {
            params.textContent = JSON.stringify(JSON.parse(action.paramsJson), null, 2);
        } catch (e) {
            params.textContent = action.paramsJson;
        }
        card.appendChild(params);

        var btnRow = document.createElement('div');
        btnRow.className = 'assistant-action-buttons';
        var confirmBtn = document.createElement('button');
        confirmBtn.type = 'button';
        confirmBtn.className = 'assistant-action-confirm';
        confirmBtn.setAttribute('data-testid', 'assistant-action-confirm');
        confirmBtn.textContent = 'Confirm';
        confirmBtn.addEventListener('click', function () {
            resolvePendingAction(action.id, 'confirm', card);
        });
        var cancelBtn = document.createElement('button');
        cancelBtn.type = 'button';
        cancelBtn.className = 'assistant-action-cancel';
        cancelBtn.setAttribute('data-testid', 'assistant-action-cancel');
        cancelBtn.textContent = 'Cancel';
        cancelBtn.addEventListener('click', function () {
            resolvePendingAction(action.id, 'cancel', card);
        });
        btnRow.appendChild(confirmBtn);
        btnRow.appendChild(cancelBtn);
        card.appendChild(btnRow);

        messagesContainer.appendChild(card);
        messagesContainer.scrollTop = messagesContainer.scrollHeight;
    }

    /** Fetch and render this thread's pending actions (confirmation cards). */
    function renderPendingActionsForThread() {
        if (!currentConversationId) return;
        fetch('/assistant/conversations/' + currentConversationId + '/pending-actions')
            .then(function (r) { return r.ok ? r.json() : []; })
            .then(function (actions) {
                (actions || []).forEach(renderPendingActionCard);
            })
            .catch(function () { /* non-fatal: cards also render from the reply */ });
    }

    function resolvePendingAction(actionId, verb, card) {
        var buttons = card.querySelectorAll('button');
        buttons.forEach(function (b) { b.disabled = true; });
        var body = currentConversationId
            ? { conversationId: String(currentConversationId) } : {};
        fetch('/assistant/actions/' + actionId + '/' + verb, {
            method: 'POST',
            headers: csrfHeaders({ 'Content-Type': 'application/json' }),
            body: JSON.stringify(body)
        })
            .then(function (r) {
                if (!r.ok) throw new Error('action ' + verb + ' failed');
                return r.json();
            })
            .then(function (reply) {
                if (reply.conversationId) currentConversationId = reply.conversationId;
                card.classList.add('resolved');
                var note = document.createElement('div');
                note.className = 'assistant-action-resolved';
                note.textContent = verb === 'confirm' ? 'Executed' : 'Cancelled';
                card.appendChild(note);
                loadConversationMessages();
                refreshHistoryLists();
            })
            .catch(function () {
                buttons.forEach(function (b) { b.disabled = false; });
                showError('Could not ' + verb + ' the action. Please try again.');
            });
    }

    // ------------------------- autonomy setting ------------------------

    var agenticEnabled = !!document.getElementById('assistant-agentic-enabled');
    var autonomySelect = document.getElementById('assistant-chat-autonomy');
    if (agenticEnabled && autonomySelect) {
        fetch('/assistant/autonomy')
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (view) {
                if (view && view.mode) autonomySelect.value = view.mode;
            })
            .catch(function () { /* keep default ALWAYS_CONFIRM */ });
        autonomySelect.addEventListener('change', function () {
            fetch('/assistant/autonomy', {
                method: 'POST',
                headers: csrfHeaders({ 'Content-Type': 'application/json' }),
                body: JSON.stringify({ mode: autonomySelect.value })
            }).catch(function () { /* session default applies server-side */ });
        });
    }

    // ------------------------- panel chrome --------------------------

    function openPanel() {
        panelOpen = true;
        panel.classList.add('open');
        // Resolve the current thread (most recent conversation) or empty state.
        fetch('/assistant/conversations')
            .then(function (r) { return r.ok ? r.json() : []; })
            .then(function (conversations) {
                if (conversations && conversations.length > 0) {
                    if (!currentConversationId) {
                        currentConversationId = conversations[0].id;
                    }
                    loadConversationMessages();
                }
                refreshHistoryLists();
            })
            .catch(function () {
                refreshHistoryLists();
            });
        inputEl.focus();
    }

    function closePanel() {
        panelOpen = false;
        // Exit fullscreen first: while .fullscreen is on, CSS keeps the
        // panel displayed regardless of the .open class, so removing
        // .open alone would leave a "closed" panel covering the screen
        // (observed on both desktop and mobile).
        if (fullscreen) setFullscreen(false);
        panel.classList.remove('open');
        closeHistoryOverlay();
    }

    function setSidebarVisible(on) {
        sidebarCollapsed = !on;
        widget.classList.toggle('sidebar-collapsed', sidebarCollapsed);
        var toggle = document.getElementById('assistant-chat-sidebar-toggle');
        if (toggle) toggle.setAttribute('aria-expanded', String(on));
    }

    // Sidebar starts collapsed on narrow screens so the chatroom gets the
    // full width; history stays reachable via the header buttons.
    function isNarrowViewport() {
        return window.innerWidth < 700;
    }

    function setFullscreen(on) {
        fullscreen = !!on;
        widget.classList.toggle('fullscreen', fullscreen);
        document.body.classList.toggle('assistant-chat-fullscreen-open', fullscreen);
        if (fullscreen) {
            closeHistoryOverlay(); // persistent sidebar replaces the overlay
            setSidebarVisible(!isNarrowViewport());
            refreshHistoryLists();
        }
    }

    function openHistoryOverlay() {
        if (fullscreen) return; // sidebar is always visible in fullscreen
        overlayOpen = true;
        var overlay = document.getElementById('assistant-chat-history-overlay');
        overlay.hidden = false;
        // next frame so the slide-in transition plays
        requestAnimationFrame(function () {
            overlay.classList.add('open');
        });
        refreshHistoryLists();
    }

    function closeHistoryOverlay() {
        overlayOpen = false;
        var overlay = document.getElementById('assistant-chat-history-overlay');
        if (!overlay) return;
        overlay.classList.remove('open');
        if (prefersReducedMotion) {
            overlay.hidden = true;
        } else {
            setTimeout(function () {
                if (!overlayOpen) overlay.hidden = true;
            }, 220); // just over the --dur transition
        }
    }

    // -------------------------- event wiring -------------------------

    bubble.addEventListener('click', function () {
        if (panelOpen) closePanel(); else openPanel();
    });

    document.getElementById('assistant-chat-close').addEventListener('click', closePanel);
    document.getElementById('assistant-chat-new').addEventListener('click', startNewChat);
    document.getElementById('assistant-chat-history-btn').addEventListener('click', function () {
        if (overlayOpen) closeHistoryOverlay(); else openHistoryOverlay();
    });
    document.getElementById('assistant-chat-overlay-close').addEventListener('click', closeHistoryOverlay);
    document.getElementById('assistant-chat-fullscreen').addEventListener('click', function () {
        setFullscreen(!fullscreen);
    });
    var sidebarToggle = document.getElementById('assistant-chat-sidebar-toggle');
    if (sidebarToggle) {
        sidebarToggle.addEventListener('click', function () {
            setSidebarVisible(sidebarCollapsed);
        });
    }

    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') {
            if (overlayOpen) closeHistoryOverlay();
            else if (fullscreen) setFullscreen(false);
        }
    });

    sendBtn.addEventListener('click', sendMessage);

    // Auto-grow the input up to its max-height
    inputEl.addEventListener('input', function () {
        inputEl.style.height = 'auto';
        inputEl.style.height = Math.min(inputEl.scrollHeight, 110) + 'px';
    });

    inputEl.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendMessage();
        }
        if (e.key === 'Escape' && pendingEditOriginal) {
            // Cancel edit mode — restore the plain composer.
            pendingEditOriginal = null;
            inputEl.classList.remove('editing');
            inputEl.placeholder = 'Ask a question...';
        }
    });
})();
