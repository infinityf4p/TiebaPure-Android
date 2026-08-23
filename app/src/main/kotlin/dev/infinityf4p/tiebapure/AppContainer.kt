package dev.infinityf4p.tiebapure

import android.content.Context
import android.os.Build
import dev.infinityf4p.tiebapure.core.data.AccountCredentialVault
import dev.infinityf4p.tiebapure.core.data.AppSettingsStore
import dev.infinityf4p.tiebapure.core.data.NetworkAccountMutationRepository
import dev.infinityf4p.tiebapure.core.data.NetworkAccountRepository
import dev.infinityf4p.tiebapure.core.data.NetworkAuthenticationRepository
import dev.infinityf4p.tiebapure.core.data.TiebaPureDatabase
import dev.infinityf4p.tiebapure.core.data.TiebaRepositories
import dev.infinityf4p.tiebapure.core.model.Account
import dev.infinityf4p.tiebapure.core.model.ReaderFontFamily
import dev.infinityf4p.tiebapure.core.model.UserProfile
import dev.infinityf4p.tiebapure.core.network.DefaultTiebaAccountService
import dev.infinityf4p.tiebapure.core.network.DefaultTiebaReadService
import dev.infinityf4p.tiebapure.core.network.DefaultTiebaWriteService
import dev.infinityf4p.tiebapure.core.network.TiebaDeviceProfile
import dev.infinityf4p.tiebapure.core.network.TiebaHttpClientFactory
import dev.infinityf4p.tiebapure.core.network.TiebaRequestBuilder
import dev.infinityf4p.tiebapure.core.network.TiebaTransport
import dev.infinityf4p.tiebapure.feature.account.clearBaiduWebSession
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    private val credentialVault = AccountCredentialVault(appContext)
    private val mutableAccount = MutableStateFlow(credentialVault.load())

    val sessionExpiration = SessionExpirationCoordinator(
        currentAccount = { mutableAccount.value },
        logOut = ::logOut,
    )

    val account: StateFlow<Account?> = mutableAccount.asStateFlow()
    val settings = AppSettingsStore(appContext)
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val currentSettings = settings.values.stateIn(
        scope = applicationScope,
        started = SharingStarted.Eagerly,
        initialValue = dev.infinityf4p.tiebapure.core.data.AppSettings(),
    )
    val database = TiebaPureDatabase.getInstance(appContext)
    val readerFonts = AppReaderFontStore(appContext)

    private val requestBuilder = TiebaRequestBuilder(deviceProfile(appContext))
    private val transport = TiebaTransport(TiebaHttpClientFactory.create())
    private val readService = DefaultTiebaReadService(
        transport = transport,
        requestBuilder = requestBuilder,
    )
    private val accountService = DefaultTiebaAccountService(transport, requestBuilder)
    private val writeService = DefaultTiebaWriteService(transport, accountService, requestBuilder)
    val authenticationRepository = NetworkAuthenticationRepository(accountService)
    val accountRepository = NetworkAccountRepository(accountService)
        .monitorSessions(sessionExpiration::report)
    val mutationRepository = NetworkAccountMutationRepository(writeService)
        .monitorSessions(sessionExpiration::report)
    val repositories = TiebaRepositories.network(readService)
        .monitorSessions(sessionExpiration::report)
    val savedThreadMedia = AppSavedThreadMediaStore(appContext)
    val savedThreads = AppSavedThreadRepository(
        database = database,
        repositories = repositories,
        account = { mutableAccount.value },
        mediaStore = savedThreadMedia,
        context = appContext,
    )
    val featureRepositories = AppFeatureRepositories(
        repositories = repositories,
        database = database,
        account = { mutableAccount.value },
        accountRepository = accountRepository,
        mutationRepository = mutationRepository,
        settings = { currentSettings.value },
        applicationScope = applicationScope,
    )
    val accountFeatures = AppAccountFeatureRepositories(
        account = account,
        saveAccount = ::replaceAccount,
        clearAccount = ::logOut,
        database = database,
        repositories = repositories,
        authenticationRepository = authenticationRepository,
        accountRepository = accountRepository,
        mutationRepository = mutationRepository,
    )
    val settingsRepository = AppSettingsRepository(settings, database, readerFonts)
    val settingsAccountActions = AppSettingsAccountActions(
        account = account,
        accountRepository = accountRepository,
        mutationRepository = mutationRepository,
        onLogout = ::clearStoredAccount,
        automaticSignStore = AutomaticSignStore(appContext),
    )
    val composerRepository = AppComposerRepository(
        account,
        database,
        mutationRepository,
        AppDraftFileStore(appContext),
        submissionAllowed = { kind ->
            val current = currentSettings.value
            contentSubmissionEnabled(kind, current.postingEnabled, current.replyingEnabled)
        },
    )
    var profilePendingEdit: UserProfile? = null
        private set

    init {
        applicationScope.launch {
            readerFonts.load()
            val persisted = settings.values.first()
            val selected = persisted.reading.fontFamily
            if (selected.importedId != null && readerFonts.entries.value.none { it.id == selected.importedId }) {
                settings.setReadingPreferences(persisted.reading.copy(fontFamily = ReaderFontFamily.System))
            }
        }
        applicationScope.launch { composerRepository.repairStorage() }
        applicationScope.launch { savedThreads.repairStorage() }
        applicationScope.launch {
            combine(account, currentSettings) { activeAccount, activeSettings ->
                activeAccount?.sessionIdentity() to activeSettings.autoSignEnabled
            }
                .distinctUntilChanged()
                .collect { (session, enabled) ->
                    if (session != null && enabled) {
                        runCatching { settingsAccountActions.signAutomaticallyIfNeeded() }
                    }
                }
        }
    }

    fun stageProfileEdit(profile: UserProfile?) {
        profilePendingEdit = profile
    }

    suspend fun replaceAccount(value: Account) {
        val previous = mutableAccount.value
        if (previous?.sessionIdentity() != value.sessionIdentity()) {
            previous?.let { mutationRepository.invalidateAndDrain(it) }
        }
        mutationRepository.activateSession(value)
        try {
            credentialVault.save(value)
            mutableAccount.value = value
        } catch (error: Throwable) {
            mutationRepository.invalidateAndDrain(value)
            previous?.let { mutationRepository.activateSession(it) }
            throw error
        }
    }

    suspend fun logOut() {
        val current = mutableAccount.value
        try {
            current?.let { mutationRepository.invalidateAndDrain(it) }
        } finally {
            if (current == null || mutableAccount.value?.sessionIdentity() == current.sessionIdentity()) {
                clearStoredAccount()
            }
        }
    }

    private fun clearStoredAccount() {
        credentialVault.clear()
        mutableAccount.value = null
        clearBaiduWebSession()
    }

    private fun deviceProfile(context: Context): TiebaDeviceProfile {
        val preferences = context.getSharedPreferences("installation", Context.MODE_PRIVATE)
        val clientId = preferences.getString(CLIENT_ID_KEY, null)
            ?.takeIf(String::isNotBlank)
            ?: UUID.randomUUID().toString().also {
                check(preferences.edit().putString(CLIENT_ID_KEY, it).commit())
            }
        val metrics = context.resources.displayMetrics
        return TiebaDeviceProfile(
            clientId = clientId,
            osVersion = Build.VERSION.RELEASE,
            model = Build.MODEL.ifBlank { "Android" },
            brand = Build.BRAND.ifBlank { "Android" },
            screenWidthPixels = metrics.widthPixels.coerceAtLeast(1),
            screenHeightPixels = metrics.heightPixels.coerceAtLeast(1),
            screenDensity = metrics.density.toDouble().coerceAtLeast(0.1),
        )
    }

    private companion object {
        const val CLIENT_ID_KEY = "tieba_client_id_v1"
    }
}
