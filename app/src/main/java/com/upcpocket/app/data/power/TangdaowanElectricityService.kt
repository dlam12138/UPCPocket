package com.upcpocket.app.data.power

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 唐岛湾电费业务层。
 *
 * 这条链**没有用户身份**：学校页面按“楼号＋房间号”返回剩余电量，
 * 所以既不是登录态业务，也不按账号缓存，更没有需要加密保存的凭据。
 * 服务端下发的 `__VIEWSTATE` 只活在一次查询里，查完即弃，不做后台保活。
 *
 * 明确不做：充值、缴费记录、批量遍历房间、后台轮询。
 */
class TangdaowanElectricityService(
    private val client: TangdaowanElectricityClient = TangdaowanElectricityClient(),
) {

    suspend fun buildings(): List<String> = withContext(Dispatchers.IO) { client.buildings() }

    suspend fun query(building: String, room: String): ElectricityResult = withContext(Dispatchers.IO) {
        client.query(ElectricityQuery(ElectricityCampus.TANGDAOWAN, building, room))
    }
}
