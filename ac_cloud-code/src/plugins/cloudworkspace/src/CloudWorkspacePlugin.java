package com.diegonmarcos.cloudcode.workspace;

import android.content.Context;

import com.diegonmarcos.superapp.repoworkspace.RepoWorkspace;
import com.diegonmarcos.superapp.repoworkspace.RepoWorkspaceClient;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/**
 * cloud-code's native half of the repository workspace (Cordova service "CloudWorkspace"): Cloud Drive owns git, this app
 * asks it over libs:repo-workspace's IRepoWorkspace binder and builds no sync engine of its own.
 *
 *  listRepos()          {drive, repos: [every repository on every volume Drive knows]}
 *  findRepo(name)       {drive, repos: [the clones of name]}      (empty list = nowhere on this phone)
 *  requestClone(name)   {ok, reason}: opens Drive's clone flow (Sync, Git, the dialog prefilled), returns at once
 *  status(path)         {drive, repo|null}
 *
 * "drive" is true when Drive is installed and bound, so the page can tell "Drive answered: none" from "Drive is absent"
 * and fall back to its own volume probe only in the second case. Binder calls block: they run on the Cordova thread pool.
 */
public class CloudWorkspacePlugin extends CordovaPlugin {
    private RepoWorkspaceClient client;

    private synchronized RepoWorkspaceClient client() {
        if (client == null) client = new RepoWorkspaceClient(cordova.getActivity().getApplicationContext());
        return client;
    }

    static JSONObject repoJson(RepoWorkspace.RepoInfo r) throws Exception {
        return new JSONObject()
            .put("name", r.getName()).put("path", r.getPath()).put("volume", r.getVolume())
            .put("branch", r.getBranch()).put("lastSyncMs", r.getLastSyncMs())
            .put("state", r.getState()).put("summary", r.getSummary());
    }

    static JSONArray reposJson(List<RepoWorkspace.RepoInfo> rows) throws Exception {
        JSONArray a = new JSONArray();
        for (RepoWorkspace.RepoInfo r : rows) a.put(repoJson(r));
        return a;
    }

    @Override
    public boolean execute(final String action, final JSONArray args, final CallbackContext cb) {
        if (!"listRepos".equals(action) && !"findRepo".equals(action) && !"requestClone".equals(action) && !"status".equals(action)) {
            return false;
        }
        cordova.getThreadPool().execute(new Runnable() {
            @Override
            public void run() {
                try {
                    RepoWorkspaceClient c = client();
                    JSONObject out = new JSONObject();
                    if ("requestClone".equals(action)) {
                        String reason = c.requestClone(cordova.getActivity(), args.optString(0, ""));
                        out.put("ok", reason.isEmpty()).put("reason", reason);
                    } else {
                        // "drive" = Drive answered: an empty list from a bound Drive is an answer, an absent Drive is not.
                        boolean drive = c.isConnected();
                        out.put("drive", drive);
                        if ("listRepos".equals(action)) out.put("repos", reposJson(drive ? c.listRepos() : new java.util.ArrayList<RepoWorkspace.RepoInfo>()));
                        else if ("findRepo".equals(action)) out.put("repos", reposJson(drive ? c.findRepo(args.optString(0, "")) : new java.util.ArrayList<RepoWorkspace.RepoInfo>()));
                        else {
                            RepoWorkspace.RepoInfo r = drive ? c.status(args.optString(0, "")) : null;
                            out.put("repo", r == null ? JSONObject.NULL : repoJson(r));
                        }
                    }
                    cb.success(out);
                } catch (Throwable t) {
                    cb.error(String.valueOf(t.getMessage()));
                }
            }
        });
        return true;
    }
}
