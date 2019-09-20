package com.psiphon3.kin;

import android.content.Context;

import com.jakewharton.rxrelay2.BehaviorRelay;
import com.psiphon3.TunnelState;

import java.math.BigDecimal;

import io.reactivex.Completable;
import io.reactivex.Maybe;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import kin.sdk.KinClient;

public class KinManager {
    private static KinManager instance;

    private final ClientHelper clientHelper;
    private final ServerCommunicator serverCommunicator;
    private final SettingsManager settingsManager;
    private final KinPermissionManager kinPermissionManager;
    private final Environment environment;

    private final BehaviorRelay<Boolean> isOptedInRelay;
    private final BehaviorRelay<Boolean> isReadyBehaviorRelay;
    private final BehaviorRelay<Boolean> isTunneledBehaviorRelay;
    private final Observable<Boolean> isOptedInObservable;
    private final Observable<AccountHelper> accountHelperObservable;

    KinManager(Context context, ClientHelper clientHelper, ServerCommunicator serverCommunicator, SettingsManager settingsManager, KinPermissionManager kinPermissionManager, Environment environment) {
        this.clientHelper = clientHelper;
        this.serverCommunicator = serverCommunicator;
        this.settingsManager = settingsManager;
        this.kinPermissionManager = kinPermissionManager;
        this.environment = environment;

        // Use a ReplaySubject with size 1, this means that it will only ever emit the latest on next
        // Start with false
        isOptedInRelay = BehaviorRelay.create();
        isReadyBehaviorRelay = BehaviorRelay.createDefault(false);
        isTunneledBehaviorRelay = BehaviorRelay.createDefault(false);

        isOptedInObservable = kinPermissionManager
                // start with the users opt-in/out
                .getUsersAgreementToKin(context)
                // use a cache to not post multiple dialogs
                // TODO: Is this the right thing to do?
                .cache()
                .toObservable()
                // after listen for further emissions from the relay
                .concatWith(isOptedInRelay)
                // only re-emit changes
                .distinctUntilChanged();

        accountHelperObservable = isOptedInObservable
                // if they opted in get an account
                .flatMapMaybe(optedIn -> {
                    if (optedIn) {
                        return clientHelper
                                .getAccount()
                                .toMaybe();
                    } else {
                        return Maybe.empty();
                    }
                })
                // then wrap in a helper
                .map(account -> clientHelper.getAccountHelper(account, environment))
                // we don't want to do this every time someone looks for the account helper, so store it
                .replay(1)
                // connect immediately
                .autoConnect(0);

        isOptedInObservable
                // let observers know we aren't ready anymore
                .doOnNext(optedIn -> {
                    if (!optedIn) {
                        isReadyBehaviorRelay.accept(false);
                    }
                })
                .subscribe();

        isTunneledObservable()
                // only take events when we become tunneled
                .filter(isTunneled -> isTunneled)
                // then get the account helper
                .flatMap(isTunneled -> accountHelperObservable)
                // take one
                .take(1)
                // call register
                .flatMapCompletable(accountHelper -> accountHelper.register(context))
                // then mark ourselves as ready
                .doOnComplete(() -> isReadyBehaviorRelay.accept(true))
                // TODO: Limit retires? What is the right way to do this?
                .retry()
                .subscribe();
    }

    static KinManager getInstance(Context context, Environment environment) {
        if (instance != null) {
            return instance;
        }

        // Set up base communication & helper classes
        KinClient kinClient = new KinClient(context, environment.getKinEnvironment(), Environment.PSIPHON_APP_ID);
        ServerCommunicator serverCommunicator = new ServerCommunicator(environment.getFriendBotServerUrl());
        ClientHelper clientHelper = new ClientHelper(kinClient, serverCommunicator);
        SettingsManager settingsManager = new SettingsManager();
        KinPermissionManager kinPermissionManager = new KinPermissionManager(settingsManager);

        return instance = new KinManager(context, clientHelper, serverCommunicator, settingsManager, kinPermissionManager, environment);
    }

    /**
     * @param context the context
     * @return the instance of the KinManager
     */
    public static KinManager getInstance(Context context) {
        // TODO: Switch to prod at some point
        return getInstance(context, Environment.TEST);
    }

    /**
     * @param context the context
     * @return the instance of the KinManager for testing
     */
    public static KinManager getTestInstance(Context context) {
        return getInstance(context, Environment.TEST);
    }

    private Observable<Boolean> isTunneledObservable() {
        return isTunneledBehaviorRelay.distinctUntilChanged().hide();
    }

    /**
     * @return false when not ready yet or opted-out; true otherwise.
     */
    public boolean isReady() {
        Boolean isReady = isReadyBehaviorRelay.getValue();
        if (isReady == null) {
            return false;
        }

        return isReady;
    }

    /**
     * @return an observable to check if the KinManager is ready.
     * Observable returns false when not ready yet or opted-out; true otherwise.
     */
    public Observable<Boolean> isReadyObservable() {
        return isReadyBehaviorRelay
                .distinctUntilChanged()
                .hide()
                .observeOn(AndroidSchedulers.mainThread());
    }

    /**
     * @return the current balance of the active account
     */
    public Single<BigDecimal> getCurrentBalance() {
        return isOptedInObservable
                .flatMap(isOptedIn -> {
                    if (isOptedIn) {
                        return isTunneledObservable();
                    } else {
                        return Observable.empty();
                    }
                })
                .filter(isTunneled -> isTunneled)
                .flatMap(isTunneled -> accountHelperObservable)
                .firstOrError()
                .flatMap(AccountHelper::getCurrentBalance)
                .onErrorReturnItem(new BigDecimal(0));
    }

    /**
     * Requests that amount of Kin gets transferred out of the active accounts wallet to Psiphon's wallet.
     * Runs synchronously, so specify a scheduler if the current scheduler isn't desired.
     *
     * @param amount the amount to be taken from the active account
     * @return a completable which fires on complete after the transaction has successfully completed
     */
    public Completable transferOut(Double amount) {
        return isOptedInObservable
                .flatMap(isOptedIn -> {
                    if (isOptedIn) {
                        return isTunneledObservable();
                    } else {
                        return Observable.empty();
                    }
                })
                .filter(isTunneled -> isTunneled)
                .flatMap(isTunneled -> accountHelperObservable)
                .firstOrError()
                .flatMapCompletable(accountHelper -> accountHelper.transferOut(amount))
                .onErrorComplete();
    }

    /**
     * Prompts the user if they're ok with spending 1 Kin to connect and charges if they are.
     *
     * @param context the context
     * @return a single which returns true on agreement to pay; otherwise false.
     */
    public Single<Boolean> chargeForConnection(Context context) {
        if (!isReady()) {
            // If we aren't ready yet just let them connect
            return Single.just(true);
        }

        return kinPermissionManager
                .confirmPay(context)
                .doOnSuccess(agreed -> {
                    if (agreed) {
                        transferOut(1d).subscribeOn(Schedulers.io()).subscribe();
                    }
                });
    }

    /**
     * @param context the context
     * @return true if the user is opted in to Kin; false otherwise.
     */
    public boolean isOptedIn(Context context) {
        return settingsManager.hasAgreedToKin(context);
    }

    /**
     * Raises the dialog to opt in to Kin.
     *
     * @param context the context
     * @return a single returning true if the user has opted in to Kin; otherwise false.
     */
    public Single<Boolean> optIn(Context context) {
        return kinPermissionManager.optIn(context)
                .doOnSuccess(optedIn -> {
                    if (optedIn) {
                        isOptedInRelay.accept(true);
                    }
                });
    }

    /**
     * Raises the dialog to opt out of Kin.
     *
     * @param context the context
     * @return a single returning true if the user has opted out of Kin; otherwise false.
     */
    public Single<Boolean> optOut(Context context) {
        return kinPermissionManager.optOut(context)
                .doOnSuccess(optedOut -> {
                    if (optedOut) {
                        // TODO: Transfer excess funds back into our account?
                        accountHelperObservable
                                .firstOrError()
                                .doOnSuccess(accountHelper -> {
                                    accountHelper.delete(context);
                                    clientHelper.deleteAccount();
                                    serverCommunicator.optOut();
                                    isOptedInRelay.accept(false);
                                })
                                .subscribe();
                    }
                });
    }

    public void onTunnelConnectionState(TunnelState tunnelState) {
        // For now we just need to update the port so don't need any relay or such to hold it
        TunnelState.ConnectionData connectionData = tunnelState.connectionData();
        int port = connectionData == null ? 0 : connectionData.httpPort();

        if (port > 0) {
            serverCommunicator.setProxyPort(port);
            isTunneledBehaviorRelay.accept(true);
        } else {
            serverCommunicator.setProxyPort(ServerCommunicator.PREVENT_CONNECTION_PORT);
            isTunneledBehaviorRelay.accept(false);
        }
    }
}
