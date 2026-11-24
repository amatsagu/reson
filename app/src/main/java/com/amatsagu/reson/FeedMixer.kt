package com.amatsagu.reson

import com.amatsagu.reson.model.Video

object FeedMixer {

    fun mix(
        followedUnwatched: List<Video>,
        recommended: List<Video>,
        excludeIds: Set<String> = emptySet(),
        limit: Int = 30,
        channelWindowSize: Int = 3,
        followedChannelIds: Set<String> = emptySet(),
        followedChannelTitles: Set<String> = emptySet(),
    ): List<Video> {
        val cleanFollowed = followedUnwatched
            .filterNot { (it.id in excludeIds) || YtDlpManager.isShortVideo(it) }
        val cleanRecommended = recommended
            .filterNot { (it.id in excludeIds) || YtDlpManager.isShortVideo(it) }

        fun isBiasedVideo(v: Video): Boolean {
            val isFollowed = (v.channelId in followedChannelIds) || (v.channelTitle in followedChannelTitles)
            return !isFollowed && YtDlpManager.hasNewsOrAmericaBias(v.title, v.channelTitle)
        }

        fun isBiasedChannelKey(key: String): Boolean {
            val isFollowed = (key in followedChannelIds) || (key in followedChannelTitles)
            return !isFollowed && YtDlpManager.hasNewsOrAmericaBias(title = "", channelTitle = key)
        }

        val followedByChannel = cleanFollowed
            .groupBy { it.channelId.ifEmpty { it.channelTitle } }
            .mapValues { ArrayDeque(it.value) }
            .toMutableMap()

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
                var chosen = keys.firstOrNull { (it !in recentChannels) && !map[it].isNullOrEmpty() }

                if (chosen == null) {
                    chosen = keys
                        .asSequence()
                        .filter { (!avoidImmediate) || (it != lastChannel) }
                        .minByOrNull { key ->
                            val idx = recentChannels.indexOf(key)
                            if (idx == -1) -1 else idx
                        }
                }

                if (chosen == null) {
                    chosen = keys.firstOrNull { !map[it].isNullOrEmpty() }
                }
                return chosen
            }

            val unbiasedKeys = map.keys.filter { !isBiasedChannelKey(it) }
            var chosenKey = if (unbiasedKeys.isNotEmpty()) pickKeyFrom(unbiasedKeys) else null

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

        while ((result.size < limit) && (result.size < totalAvailable)) {
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
