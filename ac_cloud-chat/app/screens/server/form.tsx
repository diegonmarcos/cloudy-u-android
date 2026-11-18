// Copyright (c) 2015-present Mattermost, Inc. All Rights Reserved.
// See LICENSE.txt for license information.

// Cloud fleet #1804: the ONE login screen. Server URL dropdown (mesh, public, custom), optional name, the
// Advanced section open by default (Enter secret, the existing advanced option), then Token / Login /
// Password (+ MFA when the server asks) and one action that runs the server check and the login in
// sequence with a status line per step. Every field the previous server form had is still here, with the
// same testID; the previous login screen stays too (connect without credentials still opens it).

import React, {type RefObject, useCallback, useEffect, useRef, useState} from 'react';
import {defineMessages, useIntl} from 'react-intl';
import {Keyboard, Pressable, Text, View} from 'react-native';
import Animated, {FlipInEasyX, FlipOutXUp, useAnimatedStyle, useSharedValue, withTiming} from 'react-native-reanimated';

import Button from '@components/button';
import CompassIcon from '@components/compass_icon';
import FloatingTextInput, {type FloatingTextInputRef} from '@components/floating_input/floating_text_input_label';
import FormattedText from '@components/formatted_text';
import {useAvoidKeyboard} from '@hooks/device';
import {changeOpacity, makeStyleSheetFromTheme} from '@utils/theme';
import {typography} from '@utils/typography';

import {SERVER_URL_OPTIONS, STEP_ORDER, defaultServerName, type FlowState, type StepId, type StepStatus, type UrlChoice} from './single_login';

import type {KeyboardAwareScrollView} from 'react-native-keyboard-aware-scroll-view';

const hitSlop = {top: 8, right: 8, bottom: 8, left: 8};

type Props = {
    autoFocus?: boolean;
    buttonDisabled: boolean;
    connecting: boolean;
    displayName?: string;
    displayNameError?: string;
    disableServerUrl: boolean;
    handleConnect: () => void;
    handleDisplayNameTextChanged: (text: string) => void;
    handlePreauthSecretTextChanged: (text: string) => void;
    handleUrlTextChanged: (text: string) => void;
    keyboardAwareRef: RefObject<KeyboardAwareScrollView>;
    preauthSecret?: string;
    preauthSecretError?: string;
    setShowAdvancedOptions: React.Dispatch<React.SetStateAction<boolean>>;
    showAdvancedOptions: boolean;
    theme: Theme;
    url?: string;
    urlError?: string;

    // #1804
    urlChoice: UrlChoice;
    handleUrlChoiceChanged: (choice: UrlChoice) => void;
    resolvedUrl?: string;
    token: string;
    handleTokenChanged: (text: string) => void;
    loginId: string;
    handleLoginIdChanged: (text: string) => void;
    password: string;
    handlePasswordChanged: (text: string) => void;
    mfaToken: string;
    handleMfaTokenChanged: (text: string) => void;
    showMfa: boolean;
    loginError?: string;
    flow: FlowState;
    hasCredentials: boolean;
    onForgotPassword?: () => void;
};

const getStyleSheet = makeStyleSheetFromTheme((theme: Theme) => ({
    formContainer: {
        alignItems: 'center',
        maxWidth: 600,
        width: '100%',
        paddingHorizontal: 24,
    },
    fullWidth: {
        width: '100%',
    },
    chooseText: {
        alignSelf: 'flex-start',
        color: changeOpacity(theme.centerChannelColor, 0.64),
        marginTop: 8,
        ...typography('Body', 75, 'Regular'),
    },
    advancedOptionsContainer: {
        width: '100%',
        marginTop: 16,
    },
    advancedOptionsHeader: {
        flexDirection: 'row',
        alignItems: 'center',
        justifyContent: 'flex-start',
        paddingVertical: 12,
        paddingHorizontal: 4,
    },
    advancedOptionsTitle: {
        color: theme.linkColor,
        ...typography('Body', 75, 'SemiBold'),
    },
    advancedOptionsContent: {
        width: '100%',
    },
    connectButtonContainer: {
        width: '100%',
        marginTop: 32,
        marginLeft: 20,
        marginRight: 20,
    },
    endAdornment: {
        top: 2,
    },
    inputsContainer: {
        gap: 24,
        width: '100%',
    },
    dropdownLabel: {
        color: changeOpacity(theme.centerChannelColor, 0.64),
        marginBottom: 6,
        ...typography('Body', 75, 'SemiBold'),
    },
    dropdown: {
        borderWidth: 1,
        borderColor: changeOpacity(theme.centerChannelColor, 0.16),
        borderRadius: 4,
        paddingHorizontal: 12,
        paddingVertical: 12,
        flexDirection: 'row',
        alignItems: 'center',
    },
    dropdownDisabled: {
        opacity: 0.56,
    },
    dropdownText: {
        flex: 1,
        color: theme.centerChannelColor,
        ...typography('Body', 200, 'Regular'),
    },
    dropdownSub: {
        color: changeOpacity(theme.centerChannelColor, 0.56),
        ...typography('Body', 75, 'Regular'),
    },
    dropdownList: {
        borderWidth: 1,
        borderTopWidth: 0,
        borderColor: changeOpacity(theme.centerChannelColor, 0.16),
        borderBottomLeftRadius: 4,
        borderBottomRightRadius: 4,
    },
    dropdownOption: {
        paddingHorizontal: 12,
        paddingVertical: 10,
        flexDirection: 'row',
        alignItems: 'center',
    },
    dropdownOptionSelected: {
        backgroundColor: changeOpacity(theme.buttonBg, 0.08),
    },
    pressed: {
        opacity: 0.72,
    },
    sectionTitle: {
        alignSelf: 'flex-start',
        color: theme.centerChannelColor,
        marginTop: 16,
        marginBottom: 12,
        ...typography('Heading', 200, 'SemiBold'),
    },
    orText: {
        alignSelf: 'center',
        color: changeOpacity(theme.centerChannelColor, 0.56),
        ...typography('Body', 75, 'Regular'),
    },
    forgotPassword: {
        alignSelf: 'flex-start',
        color: theme.buttonBg,
        ...typography('Body', 100, 'SemiBold'),
    },
    progress: {
        width: '100%',
        marginTop: 16,
        gap: 6,
    },
    stepRow: {
        flexDirection: 'row',
        alignItems: 'flex-start',
        gap: 8,
    },
    stepLabel: {
        color: theme.centerChannelColor,
        ...typography('Body', 100, 'SemiBold'),
    },
    stepDetail: {
        flex: 1,
        color: changeOpacity(theme.centerChannelColor, 0.64),
        ...typography('Body', 75, 'Regular'),
    },
    stepDetailError: {
        color: theme.errorTextColor,
    },
}));

const messages = defineMessages({
    connect: {
        id: 'mobile.components.select_server_view.connect',
        defaultMessage: 'Connect',
    },
    connecting: {
        id: 'mobile.components.select_server_view.connecting',
        defaultMessage: 'Connecting',
    },
    connectAndLogin: {
        id: 'mobile.server_login.connect_and_login',
        defaultMessage: 'Connect and log in',
    },
    advancedOptions: {
        id: 'mobile.components.select_server_view.advancedOptions',
        defaultMessage: 'Advanced Options',
    },
    preauthSecret: {
        id: 'mobile.server_login.enter_secret',
        defaultMessage: 'Enter secret',
    },
    preauthSecretHelp: {
        id: 'mobile.components.select_server_view.sharedSecretHelp',
        defaultMessage: 'The authentication secret shared by the administrator',
    },
    preauthSecretInvalid: {
        id: 'mobile.server.preauth_secret.invalid',
        defaultMessage: 'Authentication secret is invalid. Try again or contact your admin.',
    },
    serverUrl: {
        id: 'mobile.server_login.server_url',
        defaultMessage: 'Server URL',
    },
    customUrl: {
        id: 'mobile.components.select_server_view.enterServerUrl',
        defaultMessage: 'Enter Server URL',
    },
    displayName: {
        id: 'mobile.server_login.server_name_optional',
        defaultMessage: 'Server name (optional)',
    },
    defaultName: {
        id: 'mobile.server_login.server_name_default',
        defaultMessage: 'Left blank, the name is “{name}”',
    },
    signIn: {
        id: 'mobile.server_login.sign_in',
        defaultMessage: 'Sign in',
    },
    signInHelp: {
        id: 'mobile.server_login.sign_in_help',
        defaultMessage: 'A token, or login and password. Leave all empty to choose a login option after connecting.',
    },
    token: {
        id: 'mobile.server_login.token',
        defaultMessage: 'Token',
    },
    or: {
        id: 'login.or',
        defaultMessage: 'or',
    },
    loginId: {
        id: 'mobile.server_login.login',
        defaultMessage: 'Login (email or username)',
    },
    password: {
        id: 'login.password',
        defaultMessage: 'Password',
    },
    mfa: {
        id: 'mobile.server_login.mfa',
        defaultMessage: 'MFA code',
    },
    forgot: {
        id: 'login.forgot',
        defaultMessage: 'Forgot your password?',
    },
    step_url: {id: 'mobile.server_login.step.url', defaultMessage: 'Server URL'},
    step_reach: {id: 'mobile.server_login.step.reach', defaultMessage: 'Reachability'},
    step_version: {id: 'mobile.server_login.step.version', defaultMessage: 'Server version'},
    step_tls: {id: 'mobile.server_login.step.tls', defaultMessage: 'TLS'},
    step_login: {id: 'mobile.server_login.step.login', defaultMessage: 'Login'},
});

const STEP_LABEL: Record<StepId, keyof typeof messages> = {
    url: 'step_url',
    reach: 'step_reach',
    version: 'step_version',
    tls: 'step_tls',
    login: 'step_login',
};

const STEP_ICON: Record<StepStatus, string> = {
    idle: 'circle-outline',
    running: 'loading',
    ok: 'check-circle',
    warn: 'alert-outline',
    error: 'alert-circle-outline',
    skipped: 'minus-circle-outline',
};

const ADVANCED_OPTIONS_EXPANDED = 114;
const ADVANCED_OPTIONS_COLLAPSED = 40;

type ProgressProps = {flow: FlowState; theme: Theme};

const FlowProgress = ({flow, theme}: ProgressProps) => {
    const {formatMessage} = useIntl();
    const styles = getStyleSheet(theme);
    const color = (s: StepStatus) => {
        switch (s) {
            case 'ok': return theme.onlineIndicator;
            case 'warn': return theme.awayIndicator;
            case 'error': return theme.errorTextColor;
            case 'running': return theme.buttonBg;
            default: return changeOpacity(theme.centerChannelColor, 0.4);
        }
    };
    return (
        <View
            style={styles.progress}
            testID='server_form.progress'
        >
            {STEP_ORDER.map((step) => {
                const st = flow.steps[step];
                return (
                    <View
                        key={step}
                        style={styles.stepRow}
                        testID={`server_form.progress.${step}.${st.status}`}
                    >
                        <CompassIcon
                            name={STEP_ICON[st.status]}
                            size={18}
                            color={color(st.status)}
                        />
                        <Text style={styles.stepLabel}>{formatMessage(messages[STEP_LABEL[step]])}</Text>
                        {Boolean(st.detail) && (
                            <Text
                                style={[styles.stepDetail, st.status === 'error' && styles.stepDetailError]}
                                testID={`server_form.progress.${step}.detail`}
                            >
                                {st.detail}
                            </Text>
                        )}
                    </View>
                );
            })}
        </View>
    );
};

type DropdownProps = {
    choice: UrlChoice;
    disabled: boolean;
    onChange: (c: UrlChoice) => void;
    theme: Theme;
};

const UrlDropdown = ({choice, disabled, onChange, theme}: DropdownProps) => {
    const {formatMessage} = useIntl();
    const styles = getStyleSheet(theme);
    const [open, setOpen] = useState(false);
    const selected = SERVER_URL_OPTIONS.find((o) => o.id === choice) ?? SERVER_URL_OPTIONS[0];

    const toggle = useCallback(() => {
        if (!disabled) {
            setOpen((p) => !p);
        }
    }, [disabled]);

    const pick = useCallback((c: UrlChoice) => {
        setOpen(false);
        onChange(c);
    }, [onChange]);

    return (
        <View style={styles.fullWidth}>
            <Text style={styles.dropdownLabel}>{formatMessage(messages.serverUrl)}</Text>
            <Pressable
                accessibilityRole='button'
                accessibilityState={{expanded: open, disabled}}
                onPress={toggle}
                style={({pressed}) => [styles.dropdown, disabled && styles.dropdownDisabled, pressed && styles.pressed]}
                testID='server_form.server_url.dropdown'
            >
                <View style={styles.dropdownText}>
                    <Text style={styles.dropdownText}>{formatMessage({id: selected.labelId, defaultMessage: selected.defaultLabel})}</Text>
                    {Boolean(selected.url) && <Text style={styles.dropdownSub}>{selected.url}</Text>}
                </View>
                <CompassIcon
                    name={open ? 'chevron-up' : 'chevron-down'}
                    size={20}
                    color={changeOpacity(theme.centerChannelColor, 0.64)}
                />
            </Pressable>
            {open && (
                <View style={styles.dropdownList}>
                    {SERVER_URL_OPTIONS.map((o) => (
                        <DropdownOption
                            key={o.id}
                            id={o.id}
                            label={formatMessage({id: o.labelId, defaultMessage: o.defaultLabel})}
                            url={o.url}
                            selected={o.id === choice}
                            onPick={pick}
                            theme={theme}
                        />
                    ))}
                </View>
            )}
        </View>
    );
};

type OptionProps = {id: UrlChoice; label: string; url?: string; selected: boolean; onPick: (c: UrlChoice) => void; theme: Theme};

const DropdownOption = ({id, label, url, selected, onPick, theme}: OptionProps) => {
    const styles = getStyleSheet(theme);
    const onPress = useCallback(() => onPick(id), [id, onPick]);
    return (
        <Pressable
            accessibilityRole='menuitem'
            accessibilityState={{selected}}
            onPress={onPress}
            style={({pressed}) => [styles.dropdownOption, selected && styles.dropdownOptionSelected, pressed && styles.pressed]}
            testID={`server_form.server_url.option.${id}`}
        >
            <View style={styles.dropdownText}>
                <Text style={styles.dropdownText}>{label}</Text>
                {Boolean(url) && <Text style={styles.dropdownSub}>{url}</Text>}
            </View>
            {selected && (
                <CompassIcon
                    name='check'
                    size={18}
                    color={theme.buttonBg}
                />
            )}
        </Pressable>
    );
};

const ServerForm = ({
    autoFocus = false,
    buttonDisabled,
    connecting,
    displayName = '',
    displayNameError,
    disableServerUrl,
    handleConnect,
    handleDisplayNameTextChanged,
    handlePreauthSecretTextChanged,
    handleUrlTextChanged,
    keyboardAwareRef,
    preauthSecret = '',
    preauthSecretError,
    setShowAdvancedOptions,
    showAdvancedOptions,
    theme,
    url = '',
    urlError,
    urlChoice,
    handleUrlChoiceChanged,
    resolvedUrl,
    token,
    handleTokenChanged,
    loginId,
    handleLoginIdChanged,
    password,
    handlePasswordChanged,
    mfaToken,
    handleMfaTokenChanged,
    showMfa,
    loginError,
    flow,
    hasCredentials,
    onForgotPassword,
}: Props) => {
    const {formatMessage} = useIntl();
    const displayNameRef = useRef<FloatingTextInputRef>(null);
    const preauthSecretRef = useRef<FloatingTextInputRef>(null);
    const urlRef = useRef<FloatingTextInputRef>(null);
    const tokenRef = useRef<FloatingTextInputRef>(null);
    const loginIdRef = useRef<FloatingTextInputRef>(null);
    const passwordRef = useRef<FloatingTextInputRef>(null);
    const mfaRef = useRef<FloatingTextInputRef>(null);
    const styles = getStyleSheet(theme);
    const [isPreauthSecretVisible] = useState(false);
    const [isPasswordVisible, setIsPasswordVisible] = useState(false);

    useAvoidKeyboard(keyboardAwareRef, 1.8);

    const onConnect = useCallback(() => {
        Keyboard.dismiss();
        handleConnect();
    }, [handleConnect]);

    const onUrlSubmit = useCallback(() => {
        displayNameRef.current?.focus();
    }, []);

    const onDisplayNameSubmit = useCallback(() => {
        if (showAdvancedOptions || preauthSecretError) {
            preauthSecretRef.current?.focus();
        } else {
            tokenRef.current?.focus();
        }
    }, [showAdvancedOptions, preauthSecretError]);

    const onSecretSubmit = useCallback(() => {
        tokenRef.current?.focus();
    }, []);

    const onTokenSubmit = useCallback(() => {
        if (token.trim()) {
            onConnect();
        } else {
            loginIdRef.current?.focus();
        }
    }, [onConnect, token]);

    const onLoginIdSubmit = useCallback(() => {
        passwordRef.current?.focus();
    }, []);

    const onPasswordSubmit = useCallback(() => {
        if (showMfa) {
            mfaRef.current?.focus();
        } else {
            onConnect();
        }
    }, [onConnect, showMfa]);

    const toggleAdvancedOptions = useCallback(() => {
        setShowAdvancedOptions((prev: boolean) => !prev);
    }, [setShowAdvancedOptions]);

    const togglePasswordVisibility = useCallback(() => {
        setIsPasswordVisible((p) => !p);
    }, []);

    const chevronRotation = useSharedValue(showAdvancedOptions ? 180 : 0);
    const advancedOptionsStyle = useAnimatedStyle(() => ({
        height: showAdvancedOptions ? ADVANCED_OPTIONS_EXPANDED : withTiming(ADVANCED_OPTIONS_COLLAPSED, {duration: 250}),
    }));

    useEffect(() => {
        chevronRotation.value = withTiming(showAdvancedOptions ? 180 : 0, {duration: 250});

        // chevronRotation is a Reanimated shared value; its reference is stable and does not need to be in the dependency array.
        // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [showAdvancedOptions]);

    const chevronAnimatedStyle = useAnimatedStyle(() => ({
        transform: [{rotate: `${chevronRotation.value}deg`}],
    }));

    const connectButtonTestId = buttonDisabled ? 'server_form.connect.button.disabled' : 'server_form.connect.button';
    let buttonText = formatMessage(hasCredentials ? messages.connectAndLogin : messages.connect);
    if (connecting) {
        buttonText = formatMessage(messages.connecting);
    }

    const passwordAdornment = (
        <Pressable
            onPress={togglePasswordVisibility}
            hitSlop={hitSlop}
            style={({pressed}) => [styles.endAdornment, pressed && styles.pressed]}
        >
            <CompassIcon
                name={isPasswordVisible ? 'eye-off-outline' : 'eye-outline'}
                size={20}
                color={changeOpacity(theme.centerChannelColor, 0.64)}
            />
        </Pressable>
    );

    return (
        <View style={styles.formContainer}>
            <View style={styles.inputsContainer}>
                <UrlDropdown
                    choice={urlChoice}
                    disabled={disableServerUrl}
                    onChange={handleUrlChoiceChanged}
                    theme={theme}
                />
                {urlChoice === 'custom' && (
                    <FloatingTextInput
                        rawInput={true}
                        autoFocus={autoFocus}
                        enablesReturnKeyAutomatically={true}
                        editable={!disableServerUrl}
                        error={urlError}
                        keyboardType='url'
                        label={formatMessage(messages.customUrl)}
                        onChangeText={handleUrlTextChanged}
                        onSubmitEditing={onUrlSubmit}
                        ref={urlRef}
                        returnKeyType='next'
                        testID='server_form.server_url.input'
                        theme={theme}
                        value={url}
                    />
                )}
                {urlChoice !== 'custom' && Boolean(urlError) && (
                    <Text
                        style={[styles.stepDetail, styles.stepDetailError]}
                        testID='server_form.server_url.error'
                    >
                        {urlError}
                    </Text>
                )}
                <FloatingTextInput
                    rawInput={true}
                    enablesReturnKeyAutomatically={false}
                    error={displayNameError}
                    label={formatMessage(messages.displayName)}
                    onChangeText={handleDisplayNameTextChanged}
                    onSubmitEditing={onDisplayNameSubmit}
                    ref={displayNameRef}
                    returnKeyType='next'
                    testID='server_form.server_display_name.input'
                    theme={theme}
                    value={displayName}
                />
            </View>

            {!displayNameError &&
            <FormattedText
                defaultMessage={'Choose a display name for your server'}
                id={'mobile.components.select_server_view.displayHelp'}
                style={styles.chooseText}
                testID={'server_form.display_help'}
            />
            }
            {!displayNameError && !displayName.trim() && Boolean(resolvedUrl) &&
            <FormattedText
                {...messages.defaultName}
                values={{name: defaultServerName(resolvedUrl ?? '')}}
                style={styles.chooseText}
                testID='server_form.server_display_name.default'
            />
            }

            <Animated.View style={[styles.advancedOptionsContainer, advancedOptionsStyle]}>
                <Pressable
                    onPress={toggleAdvancedOptions}
                    style={({pressed}) => [styles.advancedOptionsHeader, pressed && styles.pressed]}
                    testID='server_form.advanced_options.toggle'
                >
                    <Animated.View style={chevronAnimatedStyle}>
                        <CompassIcon
                            name='chevron-down'
                            size={20}
                            style={styles.advancedOptionsTitle}
                        />
                    </Animated.View>
                    <FormattedText
                        defaultMessage='Advanced Options'
                        id='mobile.components.select_server_view.advancedOptions'
                        style={styles.advancedOptionsTitle}
                    />
                </Pressable>

                {showAdvancedOptions && (
                    <Animated.View
                        style={styles.advancedOptionsContent}
                        entering={FlipInEasyX.duration(250)}
                        exiting={FlipOutXUp.duration(250)}
                    >
                        <FloatingTextInput
                            rawInput={true}
                            enablesReturnKeyAutomatically={true}
                            error={preauthSecretError}
                            keyboardType={isPreauthSecretVisible ? 'visible-password' : 'default'}
                            label={formatMessage(messages.preauthSecret)}
                            onChangeText={handlePreauthSecretTextChanged}
                            onSubmitEditing={onSecretSubmit}
                            ref={preauthSecretRef}
                            returnKeyType='next'
                            secureTextEntry={!isPreauthSecretVisible}
                            testID='server_form.preauth_secret.input'
                            theme={theme}
                            value={preauthSecret}
                        />
                        <FormattedText
                            {...messages.preauthSecretHelp}
                            style={styles.chooseText}
                            testID='server_form.preauth_secret_help'
                        />
                    </Animated.View>
                )}
            </Animated.View>

            <FormattedText
                {...messages.signIn}
                style={styles.sectionTitle}
                testID='server_form.sign_in.title'
            />
            <View style={styles.inputsContainer}>
                <FloatingTextInput
                    rawInput={true}
                    autoComplete='off'
                    disableFullscreenUI={true}
                    label={formatMessage(messages.token)}
                    onChangeText={handleTokenChanged}
                    onSubmitEditing={onTokenSubmit}
                    ref={tokenRef}
                    returnKeyType='next'
                    secureTextEntry={true}
                    testID='server_form.token.input'
                    theme={theme}
                    value={token}
                />
                <Text style={styles.orText}>{formatMessage(messages.or)}</Text>
                <FloatingTextInput
                    rawInput={true}
                    autoComplete='email'
                    disableFullscreenUI={true}
                    keyboardType='email-address'
                    label={formatMessage(messages.loginId)}
                    onChangeText={handleLoginIdChanged}
                    onSubmitEditing={onLoginIdSubmit}
                    ref={loginIdRef}
                    returnKeyType='next'
                    testID='server_form.login_id.input'
                    theme={theme}
                    value={loginId}
                />
                <FloatingTextInput
                    rawInput={true}
                    autoComplete='current-password'
                    disableFullscreenUI={true}
                    endAdornment={passwordAdornment}
                    error={showMfa ? undefined : loginError}
                    keyboardType={isPasswordVisible ? 'visible-password' : 'default'}
                    label={formatMessage(messages.password)}
                    onChangeText={handlePasswordChanged}
                    onSubmitEditing={onPasswordSubmit}
                    ref={passwordRef}
                    returnKeyType={showMfa ? 'next' : 'join'}
                    secureTextEntry={!isPasswordVisible}
                    testID='server_form.password.input'
                    theme={theme}
                    value={password}
                />
                {showMfa && (
                    <FloatingTextInput
                        rawInput={true}
                        autoFocus={true}
                        disableFullscreenUI={true}
                        error={loginError}
                        keyboardType='numeric'
                        label={formatMessage(messages.mfa)}
                        onChangeText={handleMfaTokenChanged}
                        onSubmitEditing={onConnect}
                        ref={mfaRef}
                        returnKeyType='join'
                        testID='server_form.mfa.input'
                        theme={theme}
                        value={mfaToken}
                    />
                )}
            </View>
            <FormattedText
                {...messages.signInHelp}
                style={styles.chooseText}
                testID='server_form.sign_in.help'
            />
            {Boolean(onForgotPassword) && (
                <Pressable
                    onPress={onForgotPassword}
                    hitSlop={hitSlop}
                    style={({pressed}) => [styles.fullWidth, pressed && styles.pressed]}
                    testID='server_form.forgot_password.button'
                >
                    <Text style={[styles.chooseText, styles.forgotPassword]}>{formatMessage(messages.forgot)}</Text>
                </Pressable>
            )}

            <View style={styles.connectButtonContainer}>
                <Button
                    disabled={buttonDisabled}
                    onPress={onConnect}
                    testID={connectButtonTestId}
                    size='lg'
                    theme={theme}
                    text={buttonText}
                    showLoader={connecting}
                />
            </View>

            {flow.phase !== 'idle' && (
                <FlowProgress
                    flow={flow}
                    theme={theme}
                />
            )}
        </View>
    );
};

export default ServerForm;
