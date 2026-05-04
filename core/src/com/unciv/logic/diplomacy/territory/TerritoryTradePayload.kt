package com.unciv.logic.diplomacy.territory

import com.unciv.logic.GameInfo
import com.unciv.logic.map.tile.Tile

/**
 * Compact serialization of a TerritoryTradeOffer into a single string for use as
 * the value field of a PopupAlert (which only stores type + value: String).
 *
 * Format (pipe-separated fields, semicolon-separated lists):
 *   from|to|offTiles|reqTiles|offCities|reqCities|goldOff|goldReq|agreeOff|agreeReq
 *
 * Tiles are encoded as `x,y`. Cities by their unique location `x,y`. Agreements
 * by enum name. Empty lists are empty fields.
 */
object TerritoryTradePayload {

    fun encode(offer: TerritoryTradeOffer): String {
        val parts = listOf(
            offer.fromCiv.civName,
            offer.toCiv.civName,
            offer.offered.tiles.joinToString(";") { tileToken(it) },
            offer.requested.tiles.joinToString(";") { tileToken(it) },
            offer.offered.cities.joinToString(";") { tileToken(it.getCenterTile()) },
            offer.requested.cities.joinToString(";") { tileToken(it.getCenterTile()) },
            offer.offered.gold.toString(),
            offer.requested.gold.toString(),
            offer.offered.sideAgreements.joinToString(";") { it.name },
            offer.requested.sideAgreements.joinToString(";") { it.name }
        )
        return parts.joinToString("|")
    }

    fun decode(payload: String, gameInfo: GameInfo): TerritoryTradeOffer? {
        val parts = payload.split("|")
        if (parts.size < 10) return null
        val fromCiv = gameInfo.getCivilization(parts[0])
        val toCiv = gameInfo.getCivilization(parts[1])
        val offTiles = parseTiles(parts[2], gameInfo)
        val reqTiles = parseTiles(parts[3], gameInfo)
        val offCities = parseCities(parts[4], gameInfo, fromCiv.civName)
        val reqCities = parseCities(parts[5], gameInfo, toCiv.civName)
        val goldOff = parts[6].toIntOrNull() ?: 0
        val goldReq = parts[7].toIntOrNull() ?: 0
        val agreeOff = parseAgreements(parts[8])
        val agreeReq = parseAgreements(parts[9])

        return TerritoryTradeOffer(
            fromCiv = fromCiv,
            toCiv = toCiv,
            offered = TradePackage(offTiles, offCities, goldOff, agreeOff),
            requested = TradePackage(reqTiles, reqCities, goldReq, agreeReq)
        )
    }

    private fun tileToken(tile: Tile): String = "${tile.position.x.toInt()},${tile.position.y.toInt()}"

    private fun parseTiles(raw: String, gameInfo: GameInfo): List<Tile> {
        if (raw.isBlank()) return emptyList()
        return raw.split(";").mapNotNull { token ->
            val coords = token.split(",")
            if (coords.size != 2) return@mapNotNull null
            val x = coords[0].toIntOrNull() ?: return@mapNotNull null
            val y = coords[1].toIntOrNull() ?: return@mapNotNull null
            try {
                gameInfo.tileMap[com.badlogic.gdx.math.Vector2(x.toFloat(), y.toFloat())]
            } catch (e: Exception) { null }
        }
    }

    private fun parseCities(raw: String, gameInfo: GameInfo, expectedOwnerName: String) =
        parseTiles(raw, gameInfo).mapNotNull { tile ->
            val city = tile.getCity()
            if (city != null && city.civ.civName == expectedOwnerName && tile.isCityCenter()) city
            else null
        }

    private fun parseAgreements(raw: String): Set<SideAgreement> {
        if (raw.isBlank()) return emptySet()
        return raw.split(";").mapNotNull {
            try { SideAgreement.valueOf(it) } catch (e: Exception) { null }
        }.toSet()
    }
}
