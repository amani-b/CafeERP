(function () {
    'use strict';

    let panelOpen = false;
    const messagesContainer = document.getElementById('assistant-chat-messages');
    const inputEl = document.getElementById('assistant-chat-input');
    const sendBtn = document.getElementById('assistant-chat-send');
    const panel = document.getElementById('assistant-chat-panel');
    const bubble = document.getElementById('assistant-chat-bubble');

    if (!messagesContainer || !inputEl || !sendBtn || !panel || !bubble) {
        return; // widget not rendered on this page
    }

    var prefersReducedMotion = window.matchMedia &&
        window.matchMedia('(prefers-reduced-motion: reduce)').matches;

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
            a.textContent = link.label + ' \u2192';
            a.target = '_blank';
            a.rel = 'noopener';
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
        if (links && links.length > 0) {
            appendLinks(shell.root, links);
        }
        messagesContainer.scrollTop = messagesContainer.scrollHeight;
    }

    function setLoading(loading) {
        var existing = document.querySelector('.assistant-chat-loading');
        if (existing) existing.remove();
        var existingErr = document.querySelector('.assistant-chat-error');
        if (existingErr) existingErr.remove();

        if (loading) {
            var el = document.createElement('div');
            el.className = 'assistant-chat-loading';

            var dots = document.createElement('div');
            dots.className = 'assistant-chat-loading-dots';
            dots.setAttribute('aria-hidden', 'true');
            for (var i = 0; i < 3; i++) {
                dots.appendChild(document.createElement('span'));
            }
            el.appendChild(dots);

            var label = document.createElement('span');
            label.className = 'assistant-chat-loading-label';
            label.textContent = 'Thinking\u2026';
            el.appendChild(label);

            messagesContainer.appendChild(el);
            messagesContainer.scrollTop = messagesContainer.scrollHeight;
            sendBtn.disabled = true;
            inputEl.disabled = true;
        } else {
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

    function loadHistory() {
        fetch('/assistant/history')
            .then(function (r) {
                if (!r.ok) throw new Error('Failed to load history');
                return r.json();
            })
            .then(function (messages) {
                messagesContainer.innerHTML = '';
                messages.forEach(function (m) {
                    addMessage(
                        m.role === 'USER' ? 'user' : 'assistant',
                        m.content,
                        null
                    );
                });
            })
            .catch(function () {
                messagesContainer.innerHTML = '';
                showError('Could not load chat history.');
            });
    }

    function sendMessage() {
        var text = inputEl.value.trim();
        if (!text) return;

        inputEl.value = '';
        addMessage('user', text, null);
        setLoading(true);

        fetch('/assistant/chat', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ message: text })
        })
            .then(function (r) {
                if (!r.ok) throw new Error('Request failed');
                return r.json();
            })
            .then(function (reply) {
                setLoading(false);
                addAssistantMessageWithReveal(reply.text, reply.links || []);
            })
            .catch(function () {
                setLoading(false);
                showError('Failed to get a response. Please try again.');
            });
    }

    // Event handlers
    bubble.addEventListener('click', function () {
        panelOpen = !panelOpen;
        panel.classList.toggle('open', panelOpen);
        if (panelOpen) {
            loadHistory();
            inputEl.focus();
        }
    });

    document.getElementById('assistant-chat-close').addEventListener('click', function () {
        panelOpen = false;
        panel.classList.remove('open');
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