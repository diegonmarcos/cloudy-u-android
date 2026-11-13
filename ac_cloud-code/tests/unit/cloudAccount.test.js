import { describe, expect, it, vi } from "vitest";
import {
	CLOUD_ACCOUNT_ACTIVITY,
	CLOUD_ACCOUNT_EXTRAS,
	CLOUD_ACCOUNT_PACKAGE,
	openCloudAccountSignIn,
} from "../../src/lib/cloudAccount";

// #1693 the "Sign in via Cloud Account" entry: it opens Cloud Account at the Code page and starts nothing itself.
describe("openCloudAccountSignIn", () => {
	it("launches Cloud Account's activity with the page extras and resolves true", async () => {
		const launchApp = vi.fn((_p, _c, _e, ok) => ok("started"));
		const say = vi.fn();
		await expect(openCloudAccountSignIn({ launchApp }, say)).resolves.toBe(true);
		expect(launchApp).toHaveBeenCalledWith(
			CLOUD_ACCOUNT_PACKAGE,
			CLOUD_ACCOUNT_ACTIVITY,
			CLOUD_ACCOUNT_EXTRAS,
			expect.any(Function),
			expect.any(Function),
		);
		expect(CLOUD_ACCOUNT_EXTRAS).toEqual({ section: "connect", page: "hub", provider: "code" });
		expect(say).not.toHaveBeenCalled();
	});

	it("says Cloud Account is not installed when the launch fails, never throws", async () => {
		const launchApp = vi.fn((_p, _c, _e, _ok, fail) => fail("ActivityNotFound"));
		const say = vi.fn();
		await expect(openCloudAccountSignIn({ launchApp }, say)).resolves.toBe(false);
		expect(say).toHaveBeenCalledWith(expect.stringContaining("not installed"));
	});

	it("survives a launcher that throws or is missing", async () => {
		const say = vi.fn();
		await expect(openCloudAccountSignIn({ launchApp: () => { throw new Error("boom"); } }, say)).resolves.toBe(false);
		await expect(openCloudAccountSignIn(undefined, say)).resolves.toBe(false);
		expect(say).toHaveBeenCalledTimes(2);
	});
});
