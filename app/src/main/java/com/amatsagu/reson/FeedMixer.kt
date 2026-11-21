package com.amatsagu.reson

import com.amatsagu.reson.model.Video

/**
 * High-performance, anti-clustering feed mixer.
 *
 * Enforces:
 * 1. Ratio: Roughly 25% unwatched followed content to 75% recommended/discovery content (1:3 cadence).
 * 2. Anti-Clustering: Prevents videos from the same creator/channel from appearing consecutively,
 *    and disperses them across a sliding window (by default 3 consecutive items).
 * 3. Creator Round-Robin: Alternates between different creators within each pool rather
 *    than dumping multiple videos from a single creator.
 * 4. Strict De-Duplication & Exclusion: Never emits duplicates or any video in [excludeIds]
 *    (e.g. watched history or continue listening items), and silences YouTube Shorts.
 * 5. Hidden Bias Demotion: Gives lower priority to non-followed channels and videos with
 *    words "news" or "america" (bias is completely ignored if the channel is followed).
 * 6. Graceful Fallback: If one pool runs low or is empty, seamlessly fills from the other pool.
 */
object FeedMixer {

    fun mix(
        followedUnwatched: List<Video>,
        recommended: List<Video>,
        excludeIds: Set<String> = emptySet(),
        limit: Int = 30,
        channelWindowSize: Int = 3,
        followedChannelIds: Set<String> = emptySet(),
        followedChannelTitles: Set<String> = emptySet()
    ): List<Video> {
        val cleanFollowed = followedUnwatched
            .filterNot { it.id in excludeIds || YtDlpManager.isShortVideo(it) }
        val cleanRecommended = recommended
            .filterNot { it.id in excludeIds || YtDlpManager.isShortVideo(it) }

        fun isBiasedVideo(v: Video): Boolean {
            val isFollowed = v.channelId in followedChannelIds || v.channelTitle in followedChannelTitles
            if (isFollowed) return false
            return YtDlpManager.hasNewsOrAmericaBias(v.title, v.channelTitle)
        }

        fun isBiasedChannelKey(key: String): Boolean {
            if (key in followedChannelIds || key in followedChannelTitles) return false
            return YtDlpManager.hasNewsOrAmericaBias(title = "", channelTitle = key)
        }

        // Group videos by channel key (channelId if non-empty, otherwise channelTitle)
        val followedByChannel = cleanFollowed
            .groupBy { it.channelId.ifEmpty { it.channelTitle } }
            .mapValues { ArrayDeque(it.value) }
            .toMutableMap()

        // Order recommended videos so unbiased videos are queued first within each channel
        val (unbiasedRec, biasedRec) = cleanRecommended.partition { !isBiasedVideo(it) }
        val orderedRec = unbiasedRec + biasedRec

        val recommendedByChannel = orderedRec
            .groupBy { it.channelId.ifEmpty { it.channelTitle } }
            .mapValues { ArrayDeque(it.value) }
            .toMutableMap()

        val result = mutableListOf<Video>()
        val resultIds = HashSet(excludeIds)
        val recentChannels = ArrayDeque<String>()

        fun pickFromMap(map: MutableMap<String, ArrayDeque<Video>>, avoidImmediate: Boolean): Video? {
            if (map.isEmpty()) return null
            val lastChannel = recentChannels.lastOrNull()

            fun pickKeyFrom(keys: Collection<String>): String? {
                // 1. Pick a channel NOT present in recent sliding window
                var chosen = keys.firstOrNull { it !in recentChannels && !map[it].isNullOrEmpty() }

                // 2. If all in sliding window, pick one that is NOT the immediate last channel
                if (chosen == null) {
                    chosen = keys
                        .filter { !avoidImmediate || it != lastChannel }
                        .minByOrNull { key ->
                            val idx = recentChannels.indexOf(key)
                            if (idx == -1) -1 else idx
                        }
                }

                // 3. Last resort
                if (chosen == null) {
                    chosen = keys.firstOrNull { !map[it].isNullOrEmpty() }
                }
                return chosen
            }

            // Hidden bias: prioritize unbiased channels over channels with "news" or "america"
            val unbiasedKeys = map.keys.filter { !isBiasedChannelKey(it) }
            var chosenKey = if (unbiasedKeys.isNotEmpty()) pickKeyFrom(unbiasedKeys) else null

            // Fallback to biased channels if all unbiased channels are exhausted
            if (chosenKey == null) {
                chosenKey = pickKeyFrom(map.keys)
            }

            if (chosenKey == null) return null
            val queue = map[chosenKey] ?: return null
            while (queue.isNotEmpty()) {
                val candidate = queue.removeFirst()
                if (queue.isEmpty()) {
                    map.remove(chosenKey)
                }
                if (candidate.id !in resultIds) {
                    return candidate
                }
            }
            map.remove(chosenKey)
            return null
        }

        var slotIndex = 0
        val totalAvailable = cleanFollowed.size + cleanRecommended.size

        while (result.size < limit && result.size < totalAvailable) {
            // Target cadence: 25% Followed (slotIndex % 4 == 0), 75% Recommended (slotIndex % 4 != 0)
            val wantFollowed = (slotIndex % 4 == 0)
            slotIndex++

            val primaryMap = if (wantFollowed) followedByChannel else recommendedByChannel
            val fallbackMap = if (wantFollowed) recommendedByChannel else followedByChannel

            val lastChannel = recentChannels.lastOrNull()
            val primaryHasAlternative = primaryMap.keys.any { it != lastChannel }
            val fallbackHasAlternative = fallbackMap.keys.any { it != lastChannel }

            val video = if (primaryHasAlternative) {
                pickFromMap(primaryMap, avoidImmediate = true) ?: pickFromMap(fallbackMap, avoidImmediate = true)
            } else if (fallbackHasAlternative) {
                // Primary would cause an immediate consecutive repeat, but fallback can avoid it
                pickFromMap(fallbackMap, avoidImmediate = true) ?: pickFromMap(primaryMap, avoidImmediate = false)
            } else {
                pickFromMap(primaryMap, avoidImmediate = false) ?: pickFromMap(fallbackMap, avoidImmediate = false)
            }

            if (video == null) break

            result.add(video)
            resultIds.add(video.id)

            val channelKey = video.channelId.ifEmpty { video.channelTitle }
            if (channelKey.isNotBlank()) {
                recentChannels.addLast(channelKey)
                if (recentChannels.size > channelWindowSize) {
                    recentChannels.removeFirst()
                }
            }
        }

        return result
    }
}
