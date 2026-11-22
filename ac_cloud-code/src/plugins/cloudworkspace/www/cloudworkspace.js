// cloud-code's bridge to Cloud Drive's repository workspace (src/CloudWorkspacePlugin.java, libs:repo-workspace).
// Every call is a Promise and NEVER rejects for "Drive is absent": listRepos() answers {drive: false, repos: []}.
// A repo is {name, path, volume: store|emulated|external, branch, lastSyncMs, state, summary}.
var exec = require('cordova/exec');

function call(action, args) {
    return new Promise(function (resolve, reject) {
        exec(resolve, reject, 'CloudWorkspace', action, args || []);
    });
}

module.exports = {
    listRepos: function () { return call('listRepos'); },
    findRepo: function (name) { return call('findRepo', [name]); },
    requestClone: function (name) { return call('requestClone', [name]); },
    status: function (path) { return call('status', [path]); }
};
