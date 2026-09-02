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

    function buildMessageShell(role) {
        var div = document.createElement('div');
        div.className = 'assistant-chat-msg ' + role;

        var roleLabel = document.createElement('div');
        roleLabel.className = 'msg-role';
        roleLabel.textContent = role === 'user' ? 'You' : 'Assistant';
        div.appendChild(roleLabel);

        var textDiv = document.createElement('div');
        textDiv.className = 'msg-text';
        div.appendChild(textDiv);

        messagesContainer.appendChild(div);
        messagesContainer.scrollTop = messagesContainer.scrollHeight;
        return { root: div, textEl: textDiv };
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
    fetch('/assistant/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(
                currentConversationId ? { message: text, conversationId: currentConversationId }
                                      : { message: text })
        })
        .then(function (r) {
            if (!r.ok) throw new Error('Request failed');
            return r.json();
        })
        .then(function (reply) {
            setLoading(false);
            sendInFlight = false;
            // The server tells us which thread the turn persisted into.
            if (reply.conversationId) currentConversationId = reply.conversationId;
            addAssistantMessageWithReveal(reply.text, reply.links || []);
            if (reply.pendingActions && reply.pendingActions.length) {
                reply.pendingActions.forEach(renderPendingActionCard);
            }
            // The turn is now durably persisted (server writes both sides
            // synchronously before responding) — re-sync the thread from
            // the server so the pane always matches the durable history,
            // and refresh the sidebar/overlay lists (title derived).
            loadConversationMessages();
            refreshHistoryLists();
        })
        .catch(function () {
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
    });
})();
