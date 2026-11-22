// cloud-code's bridge to Cloud SuperIntel's AI engines (src/CloudAiEnginePlugin.java, libs:ai-engine).
// providers() / models() / usage() are Promises that NEVER reject for "SuperIntel is absent": they answer {si: false, ...}.
// chat(requestJson, onEvent) streams events {type: "chunk", text} ... then one {type: "done", usage} | {type: "error", reason}
//   | {type: "absent", reason} (SuperIntel is not installed / not bound: nothing was sent, the caller uses its own road)
//   | {type: "refused", reason} (SuperIntel took no such request); the Promise resolves with that last event.
// A provider is {id, label, kind: gateway|openrouter|claude-cli|local, health: up|down|unknown|nokey, reachable, model, detail}.
// A model is {id, name, group, contextLength, pricePrompt, priceCompletion, note}.
var exec = require('cordova/exec');

function call(action, args) {
    return new Promise(function (resolve, reject) {
        exec(resolve, reject, 'CloudAiEngine', action, args || []);
    });
}

module.exports = {
    providers: function () { return call('providers'); },
    models: function (providerId) { return call('models', [providerId]); },
    usage: function () { return call('usage'); },
    cancel: function (requestId) { return call('cancel', [requestId]); },
    chat: function (requestJson, onEvent) {
        return new Promise(function (resolve, reject) {
            exec(function (ev) {
                try { onEvent && onEvent(ev); } catch (e) { /* a throwing listener never stops the stream */ }
                if (ev && ev.type !== 'chunk') resolve(ev);
            }, reject, 'CloudAiEngine', 'chat', [requestJson]);
        });
    }
};
