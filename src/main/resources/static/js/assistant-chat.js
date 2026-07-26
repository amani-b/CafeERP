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

    function escapeHtml(text) {
        var div = document.createElement('div');
        div.appendChild(document.createTextNode(text));
        return div.innerHTML;
    }

    function addMessage(role, text, links) {
        var div = document.createElement('div');
        div.className = 'assistant-chat-msg ' + role;

        var roleLabel = document.createElement('div');
        roleLabel.className = 'msg-role';
        roleLabel.textContent = role === 'user' ? 'You' : 'Assistant';
        div.appendChild(roleLabel);

        var textDiv = document.createElement('div');
        textDiv.className = 'msg-text';
        textDiv.textContent = text;
        div.appendChild(textDiv);

        if (links && links.length > 0) {
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
            div.appendChild(linksDiv);
        }

        messagesContainer.appendChild(div);
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
            el.textContent = 'Thinking...';
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
                addMessage('assistant', reply.text, reply.links || []);
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

    inputEl.addEventListener('keydown', function (e) {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            sendMessage();
        }
    });
})();