// cloud-code — THE SEAM (#562). Diego: "FOCUS ON UI AND SMALL ENGINES".
//
// Every data source the Agents / Repos tabs need that is NOT a small
// on-device engine lives behind one of these functions, and nowhere else. The UI
// only ever calls them, so replacing a stub with a real provider (the agent
// runner's API, gitea, cloud-myterminal's workspace broker) is a change to this
// file alone. Each stub is marked `SEAM:` and says what it would call; the
// tester counts the markers against the exports so a new seam cannot land
// unmarked.

import fsOperation from "fileSystem";

// SEAM: agents — the LIVE half only: running status and token utilisation from
// the agent runner (my-ai claude-api, mesh-only). Assignment, model and batch
// are NOT this seam's: the backlog dist already derives them, and the tab
// renders that view (nav.json::agents.entry). Stub: an empty list plus the
// reason, which the tab shows under the view as-is.
export async function listAgents() {
	return {
		stub: true,
		items: [],
		note: "Live status and token use: agent runner not wired yet (#562). Assignments above come from the backlog.",
	};
}

// SEAM: repos — a small real engine for now: the children of the declared root
// that hold a .git directory. Replace with gitea / the workspace broker.
// Each repo carries its current branch, read from .git/HEAD (null when unreadable).
export async function listRepos(rootUrl) {
	const entries = await fsOperation(rootUrl).lsDir();
	const repos = [];
	for (const e of entries) {
		if (!e.isDirectory) continue;
		if (await fsOperation(e.url, ".git").exists()) repos.push({ ...e, branch: await branchOf(e.url) });
	}
	repos.sort((a, b) => a.name.localeCompare(b.name));
	return { stub: false, items: repos };
}

async function branchOf(repoUrl) {
	try {
		const head = String(await fsOperation(repoUrl, ".git/HEAD").readFile("utf8")).trim();
		const m = /^ref: refs\/heads\/(.+)$/.exec(head);
		return m ? m[1] : head.slice(0, 7);
	} catch {
		return null;
	}
}
