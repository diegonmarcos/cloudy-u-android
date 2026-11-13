/**
 * #1693 Cloud Account is the fleet's one sign-in. The Acode account login stays (it is the plugin marketplace's own, an upstream
 * login); this is the entry that opens Cloud Account at the Code page. Nothing here signs in or holds a credential: a missing Cloud
 * Account is a toast naming the fix, never an exception.
 */
export const CLOUD_ACCOUNT_PACKAGE = "com.diegonmarcos.cloudaccount";
export const CLOUD_ACCOUNT_ACTIVITY = `${CLOUD_ACCOUNT_PACKAGE}.MainActivity`;
export const CLOUD_ACCOUNT_EXTRAS = { section: "connect", page: "hub", provider: "code" };

/**
 * @param {{ launchApp?: Function }} [system]
 * @param {(message: string) => void} [say]
 * @returns {Promise<boolean>} whether Cloud Account opened
 */
export function openCloudAccountSignIn(system = globalThis.system, say = () => {}) {
	return new Promise((resolve) => {
		if (!system || typeof system.launchApp !== "function") {
			say("Cloud Account cannot be opened from here: open it from the launcher.");
			resolve(false);
			return;
		}
		try {
			system.launchApp(
				CLOUD_ACCOUNT_PACKAGE,
				CLOUD_ACCOUNT_ACTIVITY,
				CLOUD_ACCOUNT_EXTRAS,
				() => resolve(true),
				() => {
					say("Cloud Account is not installed: install it to sign in there.");
					resolve(false);
				},
			);
		} catch (error) {
			say("Cloud Account cannot be opened.");
			resolve(false);
		}
	});
}
