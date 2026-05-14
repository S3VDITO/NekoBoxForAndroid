package io.nekohasekai.sagernet.bg

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit

object FetchAutoRefreshManager {

    private const val WORK_NAME = "FetchAutoRefresh"

    fun reconfigure() {
        val workManager = WorkManager.getInstance(app)
        workManager.cancelUniqueWork(WORK_NAME)

        if (!DataStore.proxyFetchAutoRefresh) return
        val url = DataStore.proxyFetchUrl.trim()
        if (url.isBlank()) return

        val interval = DataStore.proxyFetchAutoRefreshInterval.coerceIn(15, 1440)

        workManager.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequest.Builder(FetchAutoRefreshWorker::class.java, interval.toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build())
                .build()
        )
        Logs.d("FetchAutoRefresh: scheduled every $interval min")
    }
}

class FetchAutoRefreshWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "connection-test"
    }

    private val nm = NotificationManagerCompat.from(applicationContext)

    private fun showNotification(title: String, text: String, progress: Int = 0, max: Int = 0, indeterminate: Boolean = false) {
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_active)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (max > 0) {
            builder.setProgress(max, progress, indeterminate)
        }

        nm.notify(NOTIFICATION_ID, builder.build())
    }

    private fun cancelNotification() {
        nm.cancel(NOTIFICATION_ID)
    }

    override suspend fun doWork(): Result {
        val url = DataStore.proxyFetchUrl.trim()
        if (url.isBlank()) {
            Logs.d("FetchAutoRefresh: no URL configured, skipping")
            return Result.success()
        }

        Logs.d("FetchAutoRefresh: starting for $url")

        try {
            // ========== Phase 1: Fetch ==========
            showNotification(
                applicationContext.getString(R.string.fetch_proxy_auto_refresh),
                applicationContext.getString(R.string.fetch_proxy_downloading),
                indeterminate = true
            )

            val client = Libcore.newHttpClient().apply {
                trySocks5(DataStore.mixedPort)
                tryH3Direct()
                when (DataStore.appTLSVersion) {
                    "1.3" -> restrictedTLS()
                }
            }

            val proxies = try {
                val response = client.newRequest().apply {
                    if (DataStore.allowInsecureOnRequest) {
                        allowInsecure()
                    }
                    setURL(url)
                    setUserAgent("NekoBox/FetchAutoRefresh")
                }.execute()
                val text = Util.getStringBox(response.contentString)
                RawUpdater.parseRaw(text) ?: emptyList()
            } catch (e: Exception) {
                Logs.w("FetchAutoRefresh: fetch failed", e)
                showNotification(
                    applicationContext.getString(R.string.fetch_proxy_auto_refresh),
                    "Fetch failed: ${e.message}"
                )
                return Result.success()
            } finally {
                client.close()
            }

            if (proxies.isEmpty()) {
                Logs.d("FetchAutoRefresh: no proxies found")
                cancelNotification()
                return Result.success()
            }

            // ========== Phase 2: Import ==========
            val groupName = applicationContext.getString(R.string.fetch_proxy_group_name)
            val existingGroup = SagerDatabase.groupDao.allGroups().find { it.name == groupName }
            val group = existingGroup
                ?: GroupManager.createGroup(
                    ProxyGroup(name = groupName, type = GroupType.BASIC)
                )

            SagerDatabase.proxyDao.deleteAll(group.id)
            val entities = proxies.mapIndexed { index, bean ->
                ProxyEntity(
                    groupId = group.id,
                    userOrder = (index + 1).toLong()
                ).apply {
                    putBean(bean)
                }
            }
            // Insert one-by-one to capture auto-generated IDs
            for (entity in entities) {
                entity.id = SagerDatabase.proxyDao.addProxy(entity)
            }

            // ========== Phase 3: Test all with progress ==========
            val total = entities.size
            val concurrent = DataStore.connectionTestConcurrent.coerceIn(1, 50)
            val semaphore = Semaphore(concurrent)
            val tested = AtomicInteger(0)
            val validCount = AtomicInteger(0)
            val invalidCount = AtomicInteger(0)
            val toDelete = mutableListOf<ProxyEntity>()

            showNotification(
                applicationContext.getString(R.string.fetch_proxy_auto_refresh),
                applicationContext.getString(R.string.fetch_proxy_auto_refresh_testing, 0, total),
                0, total
            )

            coroutineScope {
                entities.map { profile ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val urlTest = UrlTest()
                            try {
                                val latency = urlTest.doTest(profile)
                                profile.status = 1
                                profile.ping = latency
                                validCount.incrementAndGet()
                            } catch (e: Exception) {
                                profile.status = 3
                                profile.error = e.message
                                invalidCount.incrementAndGet()
                                synchronized(toDelete) { toDelete.add(profile) }
                            }

                            val done = tested.incrementAndGet()
                            showNotification(
                                applicationContext.getString(R.string.fetch_proxy_auto_refresh),
                                applicationContext.getString(R.string.fetch_proxy_auto_refresh_testing, done, total),
                                done, total
                            )
                        }
                    }
                }.awaitAll()
            }

            // ========== Phase 4: Delete unavailable ==========
            if (toDelete.isNotEmpty()) {
                SagerDatabase.proxyDao.deleteProxy(toDelete)
                Logs.d("FetchAutoRefresh: deleted ${toDelete.size} unavailable")
            }

            val validProfiles = entities.filter { it.status == 1 }
            if (validProfiles.isNotEmpty()) {
                SagerDatabase.proxyDao.updateProxy(validProfiles)
            }

            GroupManager.postReload(group.id)

            // ========== Final notification ==========
            val v = validCount.get()
            val r = invalidCount.get()
            Logs.d("FetchAutoRefresh: done - $v valid, $r removed")

            showNotification(
                applicationContext.getString(R.string.fetch_proxy_auto_refresh),
                applicationContext.getString(R.string.fetch_proxy_auto_refresh_complete, v, r)
            )

            return Result.success()
        } catch (e: Exception) {
            Logs.w("FetchAutoRefresh: error", e)
            return Result.success()
        }
    }
}
