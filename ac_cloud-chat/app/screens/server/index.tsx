// Copyright (c) 2015-present Mattermost, Inc. All Rights Reserved.
// See LICENSE.txt for license information.

import {useManagedConfig} from '@mattermost/react-native-emm';
import React, {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {defineMessages, useIntl} from 'react-intl';
import {Alert, BackHandler, View} from 'react-native';
import {KeyboardAwareScrollView} from 'react-native-keyboard-aware-scroll-view';
import {Navigation} from 'react-native-navigation';
import Animated from 'react-native-reanimated';
import {SafeAreaView} from 'react-native-safe-area-context';

import {doPing} from '@actions/remote/general';
import {login, tokenLogin} from '@actions/remote/session';
import {fetchConfigAndLicense} from '@actions/remote/systems';
import LocalConfig from '@assets/config.json';
import AppVersion from '@components/app_version';
import {Screens, Launch, DeepLink} from '@constants';
import {FORGOT_PASSWORD} from '@constants/screens';
import useDidMount from '@hooks/did_mount';
import useNavButtonPressed from '@hooks/navigation_button_pressed';
import {useScreenTransitionAnimation} from '@hooks/screen_transition_animation';
import {getServerCredentials} from '@init/credentials';
import PushNotifications from '@init/push_notifications';
import NetworkManager from '@managers/network_manager';
import SecurityManager from '@managers/security_manager';
import {getServerByDisplayName, getServerByIdentifier} from '@queries/app/servers';
import Background from '@screens/background';
import {MFA_EXPECTED_ERRORS} from '@screens/login/form';
import {dismissModal, goToScreen, loginAnimationOptions, popTopScreen, resetToHome} from '@screens/navigation';
import {getErrorMessage, getFullErrorMessage, getServerError, isErrorWithMessage, isServerError} from '@utils/errors';
import {getFleetSetup, type FleetSetupConfig} from '@utils/fleet_setup';
import {canReceiveNotifications} from '@utils/push_proxy';
import {loginOptions} from '@utils/server';
import {changeOpacity, makeStyleSheetFromTheme} from '@utils/theme';
import {getServerUrlAfterRedirect, tryOpenURL} from '@utils/url';

import ServerForm from './form';
import ServerHeader from './header';
import {
    choiceForUrl,
    effectiveServerName,
    flowReducer,
    initialFlow,
    loginMode,
    resolveServerUrl,
    runConnectAndLogin,
    type FlowDeps,
    type FlowMessages,
    type FlowState,
    type UrlChoice,
    type UrlInvalidReason,
} from './single_login';

import type {DeepLinkWithData, LaunchProps} from '@typings/launch';
import type {AvailableScreens} from '@typings/screens/navigation';

interface ServerProps extends LaunchProps {
    animated?: boolean;
    closeButtonId?: string;
    componentId: AvailableScreens;
    isModal?: boolean;
    theme: Theme;
}

let cancelPing: undefined | (() => void);

const flowMessages = defineMessages({
    urlEmpty: {id: 'mobile.server_url.empty', defaultMessage: 'Please enter a valid server URL'},
    urlScheme: {id: 'mobile.server_url.invalid_format', defaultMessage: 'URL must start with http:// or https://'},
    urlSpaces: {id: 'mobile.server_login.url_spaces', defaultMessage: 'The URL cannot contain spaces'},
    urlHost: {id: 'mobile.server_login.url_host', defaultMessage: 'The URL needs a valid host name, e.g. https://chat.example.com'},
    credentialsIncomplete: {id: 'mobile.server_login.credentials_incomplete', defaultMessage: 'Enter both login and password, or a token'},
    plainHttp: {id: 'mobile.server_login.plain_http', defaultMessage: 'HTTPS failed; connected over plain HTTP (no TLS)'},
    tlsOk: {id: 'mobile.server_login.tls_ok', defaultMessage: 'HTTPS, certificate accepted'},
    mfaRequired: {id: 'mobile.server_login.mfa_required', defaultMessage: 'This server requires an MFA code: enter it and run again'},
    connectedOnly: {id: 'mobile.server_login.connected_only', defaultMessage: 'No credentials typed: choose a login option next'},
    invalidCredentials: {id: 'login.invalid_credentials', defaultMessage: 'The email and password combination is incorrect'},
});

const URL_REASON: Record<UrlInvalidReason, keyof typeof flowMessages> = {
    empty: 'urlEmpty',
    spaces: 'urlSpaces',
    scheme: 'urlScheme',
    host: 'urlHost',
};

const securityMessages = defineMessages({
    blocked: {id: 'mobile.server_login.security_blocked', defaultMessage: 'Stopped by this device\'s security check (jailbreak or biometrics)'},
});

const isMFAError = (loginError: unknown): boolean => {
    const serverError = getServerError(loginError);
    return serverError ? MFA_EXPECTED_ERRORS.includes(serverError) : false;
};

const getStyleSheet = makeStyleSheetFromTheme((theme: Theme) => ({
    appInfo: {
        color: changeOpacity(theme.centerChannelColor, 0.56),
    },
    appVersionContainer: {
        alignItems: 'center',
        paddingHorizontal: 20,
        marginTop: 24,
    },
    flex: {
        flex: 1,
    },
    scrollContainer: {
        alignItems: 'center',
        flexGrow: 1,
        justifyContent: 'center',
    },
}));

const AnimatedSafeArea = Animated.createAnimatedComponent(SafeAreaView);

const Server = ({
    animated,
    closeButtonId,
    componentId,
    displayName: defaultDisplayName,
    extra,
    isModal,
    launchType,
    launchError,
    serverUrl: defaultServerUrl,
    theme,
}: ServerProps) => {
    const intl = useIntl();
    const managedConfig = useManagedConfig<ManagedConfig>();

    // Cloud fleet: the server Cloud Account's Fleet Setup pushed (native CloudFleetSetup, store chat_setup);
    // used after the managed config and before the local defaults, so an empty phone opens on the fleet's server.
    const [fleetSetup, setFleetSetup] = useState<FleetSetupConfig>({});
    useEffect(() => {
        let mounted = true;
        getFleetSetup().then((cfg) => {
            if (mounted) {
                setFleetSetup(cfg);
            }
        });
        return () => {
            mounted = false;
        };
    }, []);
    const keyboardAwareRef = useRef<KeyboardAwareScrollView>(null);
    const [connecting, setConnecting] = useState(false);
    const [displayName, setDisplayName] = useState<string>('');
    const [buttonDisabled, setButtonDisabled] = useState(true);
    const [preauthSecret, setPreauthSecret] = useState<string>('');
    const [url, setUrl] = useState<string>('');
    const [displayNameError, setDisplayNameError] = useState<string | undefined>();
    const [urlError, setUrlError] = useState<string | undefined>();
    const [preauthSecretError, setPreauthSecretError] = useState<string | undefined>();
    // #1804: the Advanced section (Enter secret) is open by default
    const [showAdvancedOptions, setShowAdvancedOptions] = useState<boolean>(true);
    const [urlChoice, setUrlChoice] = useState<UrlChoice>('mesh');
    const [token, setToken] = useState<string>('');
    const [loginId, setLoginId] = useState<string>('');
    const [password, setPassword] = useState<string>('');
    const [mfaToken, setMfaToken] = useState<string>('');
    const [showMfa, setShowMfa] = useState<boolean>(false);
    const [loginError, setLoginError] = useState<string | undefined>();
    const [flow, setFlow] = useState<FlowState>(initialFlow);
    const [checkedConfig, setCheckedConfig] = useState<{serverUrl: string; config: ClientConfig} | undefined>();
    const styles = getStyleSheet(theme);
    const {formatMessage} = intl;
    const disableServerUrl = Boolean(managedConfig?.allowOtherServers === 'false' && managedConfig?.serverUrl);
    const additionalServer = launchType === Launch.AddServerFromDeepLink || launchType === Launch.AddServer;

    const dismiss = () => {
        NetworkManager.invalidateClient(url);
        dismissModal({componentId});
    };

    const animatedStyles = useScreenTransitionAnimation(componentId, animated);

    useEffect(() => {
        let serverName: string | undefined = defaultDisplayName || managedConfig?.serverName || fleetSetup.serverName || LocalConfig.DefaultServerName;
        let serverUrl: string | undefined = defaultServerUrl || managedConfig?.serverUrl || fleetSetup.serverUrl || LocalConfig.DefaultServerUrl;
        let autoconnect = managedConfig?.allowOtherServers === 'false' || LocalConfig.AutoSelectServerUrl;

        if (launchType === Launch.DeepLink || launchType === Launch.AddServerFromDeepLink) {
            const deepLinkServerUrl = (extra as DeepLinkWithData).data?.serverUrl;
            if (managedConfig.serverUrl) {
                autoconnect = (managedConfig.allowOtherServers === 'false' && managedConfig.serverUrl === deepLinkServerUrl);
                if (managedConfig.serverUrl !== deepLinkServerUrl || launchError) {
                    Alert.alert('', intl.formatMessage({
                        id: 'mobile.server_url.deeplink.emm.denied',
                        defaultMessage: 'This app is controlled by an EMM and the DeepLink server url does not match the EMM allowed server',
                    }));
                }
            } else {
                autoconnect = true;
                serverUrl = deepLinkServerUrl;
            }
        } else if (launchType === Launch.AddServer) {
            serverName = defaultDisplayName;
            serverUrl = defaultServerUrl;
        }

        if (serverUrl) {
            // If a server Url is set by the managed or local configuration, use it:
            // a preset when it is one of the dropdown's servers, the custom field otherwise.
            const picked = choiceForUrl(serverUrl);
            setUrlChoice(picked.choice);
            setUrl(picked.customText || serverUrl);
        }

        if (serverName) {
            setDisplayName(serverName);
        }

        if (serverUrl && serverName && autoconnect) {
            // If no other servers are allowed or the local config for AutoSelectServerUrl is set, attempt to connect
            handleConnect(managedConfig?.serverUrl || fleetSetup.serverUrl || LocalConfig.DefaultServerUrl);
        }

        // We only want to handle connect when a smaller set of variables change
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [managedConfig?.allowOtherServers, managedConfig?.serverUrl, managedConfig?.serverName, defaultServerUrl, fleetSetup.serverUrl, fleetSetup.serverName]);

    // #1804: the name is optional and an error never locks the form; the button only needs a usable URL choice
    // (an invalid custom URL is reported inline at the URL step when pressed, with nothing cleared).
    useEffect(() => {
        setButtonDisabled(urlChoice === 'custom' && !url.trim());
    }, [url, urlChoice]);

    const resolvedUrl = useMemo(() => {
        const r = resolveServerUrl(urlChoice, url);
        return r.ok ? r.url : undefined;
    }, [urlChoice, url]);
    const hasCredentials = loginMode({token, loginId, password, mfaToken}) !== 'none';

    useEffect(() => {
        const listener = {
            componentDidAppear: () => {
                if (url) {
                    NetworkManager.invalidateClient(url);
                }
            },
        };
        const unsubscribe = Navigation.events().registerComponentListener(listener, componentId);

        return () => unsubscribe.remove();
    }, [componentId, url]);

    useDidMount(() => {
        const backHandler = BackHandler.addEventListener('hardwareBackPress', () => {
            if (LocalConfig.ShowOnboarding && animated) {
                popTopScreen(Screens.SERVER);
                return true;
            }
            if (isModal) {
                dismiss();
                return true;
            }

            return false;
        });

        PushNotifications.registerIfNeeded();

        return () => backHandler.remove();
    });

    useNavButtonPressed(closeButtonId || '', componentId, dismiss, []);

    const displayLogin = (serverUrl: string, config: ClientConfig, license: ClientLicense) => {
        const {enabledSSOs, hasLoginForm, numberSSOs, ssoOptions} = loginOptions(config, license);
        const passProps = {
            config,
            extra,
            hasLoginForm,
            launchError,
            launchType,
            license,
            serverDisplayName: effectiveServerName(displayName, serverUrl),
            serverPreauthSecret: preauthSecret.trim() || undefined,
            serverUrl,
            ssoOptions,
            theme,
        };

        const redirectSSO = !hasLoginForm && numberSSOs === 1;
        const screen = redirectSSO ? Screens.SSO : Screens.LOGIN;
        if (redirectSSO) {
            // @ts-expect-error ssoType not in definition
            passProps.ssoType = enabledSSOs[0];
        }

        // if deeplink is of type server removing the deeplink info on new login
        if (extra?.type === DeepLink.Server) {
            passProps.extra = undefined;
            passProps.launchType = Launch.Normal;
        }

        goToScreen(screen, '', passProps, loginAnimationOptions());
        setConnecting(false);
        setButtonDisabled(false);
    };

    const handleConnect = async (manualUrl?: string) => {
        if (buttonDisabled && !manualUrl) {
            return;
        }

        if (connecting && cancelPing) {
            cancelPing();
            return;
        }

        // a deep link / EMM / Fleet Setup autoconnect hands a URL: run it through the dropdown rules too
        const picked = typeof manualUrl === 'string' ? choiceForUrl(manualUrl) : {choice: urlChoice, customText: url};
        const target = resolveServerUrl(picked.choice, picked.customText);

        // errors from the previous run go; the values typed never do
        setDisplayNameError(undefined);
        setUrlError(undefined);
        setPreauthSecretError(undefined);
        setLoginError(undefined);

        if (target.ok) {
            const name = effectiveServerName(displayName, target.url);
            const server = await getServerByDisplayName(name);
            const credentials = await getServerCredentials(target.url);
            if (server && server.lastActiveAt > 0 && credentials?.token) {
                setDisplayNameError(formatMessage({
                    id: 'mobile.server_name.exists',
                    defaultMessage: 'You are using this name for another server.',
                }));
                setConnecting(false);
                return;
            }
        }

        connectAndLogin(picked.choice, picked.customText);
    };

    const handleUrlChoiceChanged = useCallback((choice: UrlChoice) => {
        setUrlChoice(choice);
        setUrlError(undefined);
        setCheckedConfig(undefined);
    }, []);

    const handleTokenChanged = useCallback((text: string) => {
        setToken(text);
        setLoginError(undefined);
    }, []);

    const handleLoginIdChanged = useCallback((text: string) => {
        setLoginId(text);
        setLoginError(undefined);
    }, []);

    const handlePasswordChanged = useCallback((text: string) => {
        setPassword(text);
        setLoginError(undefined);
    }, []);

    const handleMfaTokenChanged = useCallback((text: string) => {
        setMfaToken(text);
        setLoginError(undefined);
    }, []);

    const handleDisplayNameTextChanged = useCallback((text: string) => {
        setDisplayName(text);
        setDisplayNameError(undefined);
    }, []);

    const handleUrlTextChanged = useCallback((text: string) => {
        setUrlError(undefined);
        setUrl(text);
        setCheckedConfig(undefined);
    }, []);

    const handlePreauthSecretTextChanged = useCallback((text: string) => {
        setPreauthSecret(text);

        // Clear any connection errors when preauth secret is modified
        if (urlError) {
            setUrlError(undefined);
        }
        if (preauthSecretError) {
            setPreauthSecretError(undefined);
        }
    }, [urlError, preauthSecretError]);

    const getLoginErrorMessage = (loginErr: unknown) => {
        if (isServerError(loginErr)) {
            const errorId = loginErr.server_error_id;
            if (errorId === 'api.user.login.invalid_credentials_email_username' || (!isErrorWithMessage(loginErr) && typeof loginErr !== 'string')) {
                return formatMessage(flowMessages.invalidCredentials);
            }
        }
        return getFullErrorMessage(loginErr);
    };

    const flowText: FlowMessages = {
        urlInvalid: (reason) => formatMessage(flowMessages[URL_REASON[reason]]),
        credentialsIncomplete: formatMessage(flowMessages.credentialsIncomplete),
        plainHttp: formatMessage(flowMessages.plainHttp),
        tlsOk: formatMessage(flowMessages.tlsOk),
        mfaRequired: formatMessage(flowMessages.mfaRequired),
        connectedOnly: formatMessage(flowMessages.connectedOnly),
    };

    // The steps the flow runs, built from the checks the previous Connect button ran (same order, same
    // messages), plus the login the previous login screen ran.
    const flowDeps: FlowDeps<ClientConfig, ClientLicense> = {
        reach: async (target) => {
            const secret = preauthSecret.trim() || undefined;
            const first = await getServerUrlAfterRedirect(target, false, secret);
            if (first.url) {
                return {url: first.url, viaHttp: false};
            }
            const second = await getServerUrlAfterRedirect(target.replace(/^https:/i, 'http:'), true, secret);
            if (second.url) {
                return {url: second.url, viaHttp: true};
            }
            return {error: getErrorMessage(first.error, intl)};
        },
        version: async (target) => {
            const secret = preauthSecret.trim() || undefined;
            const result = await doPing(
                target,
                true, // verifyPushProxy
                managedConfig?.timeout ? parseInt(managedConfig?.timeout, 10) : undefined, // timeoutInterval
                secret,
            );
            if (result.error) {
                if (result.isPreauthError) {
                    return {
                        error: formatMessage({
                            id: 'mobile.server.preauth_secret.invalid',
                            defaultMessage: 'Authentication secret is invalid. Try again or contact your admin.',
                        }),
                        isPreauthError: true,
                    };
                }
                return {error: getErrorMessage(result.error, intl)};
            }

            canReceiveNotifications(target, result.canReceiveNotifications as string, intl);
            const data = await fetchConfigAndLicense(target, true);
            if (data.error) {
                return {error: getErrorMessage(data.error, intl)};
            }
            if (!data.config?.DiagnosticId) {
                return {
                    error: formatMessage({
                        id: 'mobile.diagnostic_id.empty',
                        defaultMessage: 'A DiagnosticId value is missing for this server. Contact your system admin to review this value and restart the server.',
                    }),
                };
            }
            if (data.config.MobileJailbreakProtection === 'true') {
                const isJailbroken = await SecurityManager.isDeviceJailbroken(target, data.config.SiteName);
                if (isJailbroken) {
                    return {error: formatMessage(securityMessages.blocked)};
                }
            }
            if (data.config.MobileEnableBiometrics === 'true') {
                const biometricsResult = await SecurityManager.authenticateWithBiometrics(target, data.config.SiteName);
                if (!biometricsResult) {
                    return {error: formatMessage(securityMessages.blocked)};
                }
            }
            const server = await getServerByIdentifier(data.config.DiagnosticId);
            const credentials = await getServerCredentials(target);
            if (server && server.lastActiveAt > 0 && credentials?.token) {
                return {
                    error: formatMessage({
                        id: 'mobile.server_identifier.exists',
                        defaultMessage: 'You are already connected to this server.',
                    }),
                };
            }
            setCheckedConfig({serverUrl: target, config: data.config});
            return {config: data.config, license: data.license!, version: data.config.Version};
        },
        login: async (target, mode, creds, config, license) => {
            const serverDisplayName = effectiveServerName(displayName, target);
            if (mode === 'token') {
                const r = await tokenLogin(target, serverDisplayName, config.DiagnosticId, creds.token, preauthSecret.trim() || undefined);
                return r.failed ? {error: getLoginErrorMessage(r.error)} : {ok: true};
            }
            const r = await login(target, {
                serverDisplayName,
                loginId: creds.loginId.trim().toLowerCase(),
                password: creds.password,
                mfaToken: creds.mfaToken.trim() || undefined,
                config,
                license,
            });
            if (r.failed && isMFAError(r.error)) {
                return {mfaRequired: true};
            }
            return r.failed ? {error: getLoginErrorMessage(r.error)} : {ok: true};
        },
    };

    const connectAndLogin = async (choice: UrlChoice, customText: string) => {
        let canceled = false;
        setConnecting(true);
        cancelPing = () => {
            canceled = true;
            setConnecting(false);
            cancelPing = undefined;
        };

        const outcome = await runConnectAndLogin(
            {choice, customText, credentials: {token, loginId, password, mfaToken}},
            flowDeps,
            flowText,
            setFlow,
            () => canceled,
        );
        cancelPing = undefined;
        setConnecting(false);

        // inline at the step that failed; every typed value stays where it is
        switch (outcome.kind) {
            case 'failed':
                if (outcome.step === 'login') {
                    setLoginError(outcome.message);
                } else if (outcome.isPreauthError) {
                    setPreauthSecretError(outcome.message);
                    setShowAdvancedOptions(true);
                } else {
                    setUrlError(outcome.message);
                }
                return;
            case 'mfa_required':
                setShowMfa(true);
                setLoginError(flowText.mfaRequired);
                return;
            case 'connected':
                // nothing typed in Sign in: the existing login screen (SSO, magic link, …) follows, as before
                displayLogin(outcome.serverUrl, outcome.config, outcome.license);
                return;
            case 'logged_in':
                resetToHome({extra, launchError, launchType, serverUrl: outcome.serverUrl});
                return;
            default:
                setFlow((f) => flowReducer(f, {type: 'reset'}));
        }
    };

    const forgotPasswordAllowed = Boolean(checkedConfig) &&
        (checkedConfig?.config.EnableSignInWithEmail === 'true' || checkedConfig?.config.EnableSignInWithUsername === 'true') &&
        checkedConfig?.config.PasswordEnableForgotLink !== 'false';

    const onForgotPassword = useCallback(() => {
        if (!checkedConfig) {
            return;
        }
        if (checkedConfig.config.ForgotPasswordLink) {
            tryOpenURL(checkedConfig.config.ForgotPasswordLink);
            return;
        }
        goToScreen(FORGOT_PASSWORD, '', {theme, serverUrl: checkedConfig.serverUrl}, loginAnimationOptions());
    }, [checkedConfig, theme]);

    return (
        <View
            style={styles.flex}
            testID='server.screen'
            nativeID={SecurityManager.getShieldScreenId(componentId, false, true)}
        >
            <Background theme={theme}/>
            <AnimatedSafeArea
                key={'server_content'}
                style={[styles.flex, animatedStyles]}
            >
                <KeyboardAwareScrollView
                    bounces={false}
                    contentContainerStyle={styles.scrollContainer}
                    enableAutomaticScroll={false}
                    enableOnAndroid={false}
                    enableResetScrollToCoords={true}
                    extraScrollHeight={20}
                    keyboardDismissMode='on-drag'
                    keyboardShouldPersistTaps='handled'
                    ref={keyboardAwareRef}
                    scrollToOverflowEnabled={true}
                    style={styles.flex}
                >
                    <ServerHeader
                        additionalServer={additionalServer}
                        theme={theme}
                    />
                    <ServerForm
                        autoFocus={additionalServer}
                        buttonDisabled={buttonDisabled}
                        connecting={connecting}
                        displayName={displayName}
                        displayNameError={displayNameError}
                        disableServerUrl={disableServerUrl}
                        handleConnect={handleConnect}
                        handleDisplayNameTextChanged={handleDisplayNameTextChanged}
                        handlePreauthSecretTextChanged={handlePreauthSecretTextChanged}
                        handleUrlTextChanged={handleUrlTextChanged}
                        keyboardAwareRef={keyboardAwareRef}
                        preauthSecret={preauthSecret}
                        preauthSecretError={preauthSecretError}
                        setShowAdvancedOptions={setShowAdvancedOptions}
                        showAdvancedOptions={showAdvancedOptions}
                        theme={theme}
                        url={url}
                        urlError={urlError}
                        urlChoice={urlChoice}
                        handleUrlChoiceChanged={handleUrlChoiceChanged}
                        resolvedUrl={resolvedUrl}
                        token={token}
                        handleTokenChanged={handleTokenChanged}
                        loginId={loginId}
                        handleLoginIdChanged={handleLoginIdChanged}
                        password={password}
                        handlePasswordChanged={handlePasswordChanged}
                        mfaToken={mfaToken}
                        handleMfaTokenChanged={handleMfaTokenChanged}
                        showMfa={showMfa}
                        loginError={loginError}
                        flow={flow}
                        hasCredentials={hasCredentials}
                        onForgotPassword={forgotPasswordAllowed ? onForgotPassword : undefined}
                    />
                    <View style={styles.appVersionContainer}>
                        <AppVersion
                            textStyle={styles.appInfo}
                            isWrapped={false}
                        />
                    </View>
                </KeyboardAwareScrollView>
            </AnimatedSafeArea>
        </View>
    );
};

export default Server;
