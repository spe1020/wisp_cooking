package com.wisp.app.repo

import com.wisp.app.nostr.ClientMessage
import com.wisp.app.nostr.Filter
import com.wisp.app.relay.RelayConfig
import com.wisp.app.relay.RelayEvent
import com.wisp.app.relay.RelayPool
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fetch a peer's kind 10002 relay list (NIP-65) from indexer relays + the connected pool,
 * then populate [RelayListRepository] with the freshest result.
 *
 * Shared by [com.wisp.app.viewmodel.DmConversationViewModel] (peer DM send) and
 * [PrivateReplyPublisher] (private reply send) so both code paths fall back to a fresh
 * relay-list fetch when the recipient hasn't published a kind 10050 DM relay set.
 */
object PeerRelayListLookup {
    suspend fun fetch(
        pubkey: String,
        relayPool: RelayPool,
        relayListRepo: RelayListRepository
    ) {
        val subId = "rl_${pubkey.take(8)}"
        val filter = Filter(
            kinds = listOf(10002),
            authors = listOf(pubkey),
            limit = 1
        )
        val reqMsg = ClientMessage.req(subId, filter)
        for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
            relayPool.sendToRelayOrEphemeral(url, reqMsg, skipBadCheck = true)
        }
        relayPool.sendToAll(reqMsg)

        val results = mutableListOf<RelayEvent>()
        withTimeoutOrNull(4000L) {
            relayPool.relayEvents
                .filter { it.subscriptionId == subId }
                .collect { results.add(it) }
        }

        val closeMsg = ClientMessage.close(subId)
        for (url in RelayConfig.DEFAULT_INDEXER_RELAYS) {
            relayPool.sendToRelay(url, closeMsg)
        }
        relayPool.sendToAll(closeMsg)

        val best = results.maxByOrNull { it.event.created_at }
        if (best != null) {
            relayListRepo.updateFromEvent(best.event)
        }
    }
}
