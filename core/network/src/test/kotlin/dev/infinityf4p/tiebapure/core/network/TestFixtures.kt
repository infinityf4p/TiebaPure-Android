package dev.infinityf4p.tiebapure.core.network

import dev.infinityf4p.tiebapure.core.model.Account

internal fun testAccount() = Account(
    uid = "42",
    name = "raw_name",
    displayName = "Display Name",
    portrait = "portrait",
    bduss = "bduss",
    stoken = "stoken",
    baiduId = "baiduid",
    tbs = "tbs",
)

internal fun testRequestBuilder() = TiebaRequestBuilder(
    device = TiebaDeviceProfile(
        clientId = "client",
        osVersion = "17",
        model = "Pixel",
        brand = "Google",
        screenWidthPixels = 1080,
        screenHeightPixels = 2400,
        screenDensity = 3.0,
        androidId = "android",
    ),
    clock = EpochMillisecondsClock { 1_725_000_000_000 },
)
