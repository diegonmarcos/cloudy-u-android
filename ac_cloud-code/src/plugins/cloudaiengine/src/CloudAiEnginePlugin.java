package com.diegonmarcos.cloudcode.aiengine;

import com.diegonmarcos.superapp.aiengine.AiEngine;
import com.diegonmarcos.superapp.aiengine.AiEngineClient;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * cloud-code's native half of the AI engine (Cordova service "CloudAiEngine"): Cloud SuperIntel holds the engines and the keys, this app
 * asks it over libs:ai-engine's IAiEngine binder and sends no key and no gateway request of its own on this road.
 *
 *  providers()           {si, providers: [{id, label, kind, health, reachable, model, detail}]}
 *  models(providerId)    {si, models: [{id, name, group, contextLength, pricePrompt, priceCompletion, note}]}
 *  usage()               {si, usage: {..the cost summary SuperIntel meters..}}
 *  cancel(requestId)     {si}
 *  chat(requestJson)     a stream of {type: chunk|done|error|absent|refused, ...}; every event but the last keeps the callback open
 *
 * "si" is true when SuperIntel is installed and bound, so the page can tell "SuperIntel answered: none" from "SuperIntel is absent" and
 * fall back to its own gateway road only in the second case. Binder calls block: they run on the Cordova thread pool.
 */
public class CloudAiEnginePlugin extends CordovaPlugin {
    private AiEngineClient client;

    private synchronized AiEngineClient client() {
        if (client == null) client = new AiEngineClient(cordova.getActivity().getApplicationContext());
        return client;
    }

    static JSONObject providerJson(AiEngine.ProviderInfo p) throws Exception {
        return new JSONObject()
            .put("id", p.getId()).put("label", p.getLabel()).put("kind", p.getKind())
            .put("health", p.getHealth()).put("reachable", p.getReachable())
            .put("model", p.getModel()).put("detail", p.getDetail());
    }

    static JSONObject modelJson(AiEngine.ModelInfo m) throws Exception {
        return new JSONObject()
            .put("id", m.getId()).put("name", m.getName()).put("group", m.getGroup())
            .put("contextLength", m.getContextLength()).put("pricePrompt", m.getPricePrompt())
            .put("priceCompletion", m.getPriceCompletion()).put("note", m.getNote());
    }

    private static void emit(CallbackContext cb, JSONObject ev, boolean last) {
        PluginResult r = new PluginResult(PluginResult.Status.OK, ev);
        r.setKeepCallback(!last);
        cb.sendPluginResult(r);
    }

    @Override
    public boolean execute(final String action, final JSONArray args, final CallbackContext cb) {
        if (!"providers".equals(action) && !"models".equals(action) && !"usage".equals(action)
            && !"cancel".equals(action) && !"chat".equals(action)) {
            return false;
        }
        cordova.getThreadPool().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    AiEngineClient c = client();
                    if ("chat".equals(action)) {
                        chat(c, args.optString(0, ""), cb);
                        return;
                    }
                    JSONObject out = new JSONObject();
                    // "si" = SuperIntel answered: an empty list from a bound SuperIntel is an answer, an absent one is not.
                    boolean si = c.isConnected();
                    out.put("si", si);
                    if ("providers".equals(action)) {
                        JSONArray a = new JSONArray();
                        if (si) for (AiEngine.ProviderInfo p : c.providers()) a.put(providerJson(p));
                        out.put("providers", a);
                    } else if ("models".equals(action)) {
                        JSONArray a = new JSONArray();
                        if (si) for (AiEngine.ModelInfo m : c.models(args.optString(0, ""))) a.put(modelJson(m));
                        out.put("models", a);
                    } else if ("usage".equals(action)) {
                        out.put("usage", new JSONObject(si ? c.usage() : "{}"));
                    } else {
                        if (si) c.cancel(args.optString(0, ""));
                    }
                    cb.success(out);
                } catch (Throwable t) {
                    cb.error(String.valueOf(t.getMessage()));
                }
            }
        });
        return true;
    }

    private void chat(AiEngineClient c, String requestJson, final CallbackContext cb) throws Exception {
        // A refusal (SuperIntel absent, bad request) makes NO callback: say so once and let the page use its own road.
        final boolean[] ended = {false};
        String refused = c.chat(requestJson, new AiEngine.Listener() {
            @Override public void onChunk(String text) {
                try { emit(cb, new JSONObject().put("type", "chunk").put("text", text), false); } catch (Exception ignored) { }
            }
            @Override public void onDone(String usageJson) {
                synchronized (ended) { if (ended[0]) return; ended[0] = true; }
                try { emit(cb, new JSONObject().put("type", "done").put("usage", usageJson), true); } catch (Exception ignored) { }
            }
            @Override public void onError(String reason) {
                synchronized (ended) { if (ended[0]) return; ended[0] = true; }
                try { emit(cb, new JSONObject().put("type", "error").put("reason", reason), true); } catch (Exception ignored) { }
            }
        });
        if (refused != null && !refused.isEmpty()) {
            synchronized (ended) { ended[0] = true; }
            boolean absent = !c.isServingAppInstalled() || refused.startsWith(AiEngine.NOT_INSTALLED) || refused.startsWith("Cloud SuperIntel did not");
            emit(cb, new JSONObject().put("type", absent ? "absent" : "refused").put("reason", refused), true);
        }
    }
}
