package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.FetchAutoRefreshManager
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.databinding.LayoutNetworkBinding
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import java.util.concurrent.atomic.AtomicInteger

class NetworkFragment : NamedFragment(R.layout.layout_network) {

    override fun name0() = app.getString(R.string.tools_network)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutNetworkBinding.bind(view)

        // STUN test
        binding.stunTest.setOnClickListener {
            startActivity(Intent(requireContext(), StunActivity::class.java))
        }

        // Fetch proxy list
        binding.fetchProxyUrl.setText(DataStore.proxyFetchUrl)

        // Auto-refresh toggle
        binding.fetchProxyAutoRefresh.isChecked = DataStore.proxyFetchAutoRefresh
        binding.fetchProxyInterval.setText(DataStore.proxyFetchAutoRefreshInterval.toString())
        updateAutoRefreshUI(binding)

        binding.fetchProxyAutoRefresh.setOnCheckedChangeListener { _, isChecked ->
            DataStore.proxyFetchAutoRefresh = isChecked
            updateAutoRefreshUI(binding)
            FetchAutoRefreshManager.reconfigure()
        }

        binding.fetchProxyInterval.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                saveInterval(binding)
            }
        }

        binding.fetchProxyDownload.setOnClickListener {
            val url = binding.fetchProxyUrl.text?.toString()?.trim() ?: ""
            if (url.isBlank()) {
                binding.fetchProxyUrlLayout.error = getString(R.string.fetch_proxy_url_empty_error)
                return@setOnClickListener
            }
            binding.fetchProxyUrlLayout.error = null
            DataStore.proxyFetchUrl = url

            // Save interval too when downloading
            saveInterval(binding)

            binding.fetchProxyDownload.isEnabled = false
            binding.fetchProxyDownload.setText(R.string.fetch_proxy_downloading)
            binding.fetchProxyStatus.visibility = View.VISIBLE
            binding.fetchProxyStatus.setText(R.string.fetch_proxy_downloading)

            runOnLifecycleDispatcher {
                try {
                    val client = Libcore.newHttpClient().apply {
                        trySocks5(DataStore.mixedPort)
                        tryH3Direct()
                        when (DataStore.appTLSVersion) {
                            "1.3" -> restrictedTLS()
                        }
                    }
                    try {
                        // ====== Phase 1: Fetch ======
                        val response = client.newRequest().apply {
                            if (DataStore.allowInsecureOnRequest) {
                                allowInsecure()
                            }
                            setURL(url)
                            setUserAgent(USER_AGENT)
                        }.execute()
                        val text = Util.getStringBox(response.contentString)
                        val proxies = RawUpdater.parseRaw(text)
                            ?: error(getString(R.string.no_proxies_found))

                        if (proxies.isEmpty()) {
                            error(getString(R.string.no_proxies_found))
                        }

                        // Find or create group
                        val groupName = getString(R.string.fetch_proxy_group_name)
                        val existingGroup = SagerDatabase.groupDao.allGroups()
                            .find { it.name == groupName }
                        val group = existingGroup
                            ?: GroupManager.createGroup(
                                ProxyGroup(name = groupName, type = GroupType.BASIC)
                            )

                        // Replace all profiles: delete old, insert new
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

                        // ====== Phase 2: Test all + show progress ======
                        onMainDispatcher {
                            binding.fetchProxyStatus.text =
                                getString(R.string.fetch_proxy_auto_refresh_testing, 0, entities.size)
                        }

                        val total = entities.size
                        val concurrent = DataStore.connectionTestConcurrent.coerceIn(1, 50)
                        val semaphore = Semaphore(concurrent)
                        val tested = AtomicInteger(0)
                        val toDelete = mutableListOf<ProxyEntity>()

                        coroutineScope {
                            entities.map { profile ->
                                async(Dispatchers.IO) {
                                    semaphore.withPermit {
                                        val urlTest = UrlTest()
                                        try {
                                            val latency = urlTest.doTest(profile)
                                            profile.status = 1
                                            profile.ping = latency
                                        } catch (e: Exception) {
                                            profile.status = 3
                                            profile.error = e.message
                                            synchronized(toDelete) { toDelete.add(profile) }
                                        }

                                        val done = tested.incrementAndGet()
                                        onMainDispatcher {
                                            binding.fetchProxyStatus.text =
                                                getString(R.string.fetch_proxy_auto_refresh_testing, done, total)
                                        }
                                    }
                                }
                            }.awaitAll()
                        }

                        // ====== Phase 3: Delete unavailable ======
                        val validCount = total - toDelete.size
                        if (toDelete.isNotEmpty()) {
                            SagerDatabase.proxyDao.deleteProxy(toDelete)
                        }
                        val validProfiles = entities.filter { it.status == 1 }
                        if (validProfiles.isNotEmpty()) {
                            SagerDatabase.proxyDao.updateProxy(validProfiles)
                        }
                        GroupManager.postReload(group.id)

                        // ====== Done ======
                        onMainDispatcher {
                            binding.fetchProxyDownload.isEnabled = true
                            binding.fetchProxyDownload.setText(R.string.fetch_proxy_download)
                            binding.fetchProxyStatus.text = getString(
                                R.string.fetch_proxy_auto_refresh_complete,
                                validCount,
                                toDelete.size
                            )
                            Toast.makeText(
                                app,
                                getString(
                                    R.string.fetch_proxy_auto_refresh_complete,
                                    validCount,
                                    toDelete.size
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    } finally {
                        client.close()
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Logs.w(e)
                    onMainDispatcher {
                        binding.fetchProxyDownload.isEnabled = true
                        binding.fetchProxyDownload.setText(R.string.fetch_proxy_download)
                        binding.fetchProxyStatus.text = e.readableMessage
                        Toast.makeText(app, e.readableMessage, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun saveInterval(binding: LayoutNetworkBinding) {
        val interval = binding.fetchProxyInterval.text?.toString()?.toIntOrNull()
            ?.coerceIn(15, 1440) ?: 15
        binding.fetchProxyInterval.setText(interval.toString())
        if (DataStore.proxyFetchAutoRefreshInterval != interval) {
            DataStore.proxyFetchAutoRefreshInterval = interval
            if (DataStore.proxyFetchAutoRefresh) {
                FetchAutoRefreshManager.reconfigure()
            }
        }
    }

    private fun updateAutoRefreshUI(binding: LayoutNetworkBinding) {
        val enabled = binding.fetchProxyAutoRefresh.isChecked
        binding.fetchProxyIntervalLayout.isEnabled = enabled
        binding.fetchProxyInterval.isEnabled = enabled
        binding.fetchProxyInterval.isClickable = enabled
        binding.fetchProxyInterval.isFocusable = enabled
    }

}
