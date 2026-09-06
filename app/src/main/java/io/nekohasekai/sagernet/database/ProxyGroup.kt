package io.nekohasekai.sagernet.database

import androidx.room.*
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.GroupOrder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.Serializable
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.applyDefaultValues

@Entity(tableName = "proxy_groups")
data class ProxyGroup(
    @PrimaryKey(autoGenerate = true) var id: Long = 0L,
    var userOrder: Long = 0L,
    var ungrouped: Boolean = false,
    var name: String? = null,
    var type: Int = GroupType.BASIC,
    var subscription: SubscriptionBean? = null,
    var order: Int = GroupOrder.ORIGIN,
    var isSelector: Boolean = false,
    var frontProxy: Long = -1L,
    var landingProxy: Long = -1L,
    /**
     * kl: 卡片创建时间（epoch ms），用于「本地」分段按创建时间排序 + 卡片显示「创建·M-d」。
     * defaultValue 不能少：Room AutoMigration 给存量行回填 0，KSP 才肯放行。
     */
    @ColumnInfo(defaultValue = "0")
    var createdAt: Long = 0L,
) : Serializable() {

    @Transient
    var export = false

    override fun initializeDefaultValues() {
        subscription?.applyDefaultValues()
    }

    override fun serializeToBuffer(output: ByteBufferOutput) {
        if (export) {

            output.writeInt(0)
            output.writeString(name)
            output.writeInt(type)
            val subscription = subscription!!
            subscription.serializeForShare(output)

        } else {
            output.writeInt(0)
            output.writeLong(id)
            output.writeLong(userOrder)
            output.writeBoolean(ungrouped)
            output.writeString(name)
            output.writeInt(type)

            if (type == GroupType.SUBSCRIPTION) {
                subscription?.serializeToBuffer(output)
            }
            output.writeInt(order)
        }
    }

    override fun deserializeFromBuffer(input: ByteBufferInput) {
        if (export) {
            val version = input.readInt()

            name = input.readString()
            type = input.readInt()
            val subscription = SubscriptionBean()
            this.subscription = subscription

            subscription.deserializeFromShare(input)
        } else {
            val version = input.readInt()

            id = input.readLong()
            userOrder = input.readLong()
            ungrouped = input.readBoolean()
            name = input.readString()
            type = input.readInt()

            if (type == GroupType.SUBSCRIPTION) {
                val subscription = SubscriptionBean()
                this.subscription = subscription

                subscription.deserializeFromBuffer(input)
            }
            order = input.readInt()
        }
    }

    fun displayName(): String {
        return name.takeIf { !it.isNullOrBlank() } ?: app.getString(R.string.group_default)
    }

    @androidx.room.Dao
    interface Dao {

        @Query("SELECT * FROM proxy_groups ORDER BY userOrder")
        fun allGroups(): List<ProxyGroup>

        @Query("SELECT * FROM proxy_groups WHERE type = ${GroupType.SUBSCRIPTION}")
        suspend fun subscriptions(): List<ProxyGroup>

        @Query("SELECT MAX(userOrder) + 1 FROM proxy_groups")
        fun nextOrder(): Long?

        @Query("SELECT * FROM proxy_groups WHERE id = :groupId")
        fun getById(groupId: Long): ProxyGroup?

        @Query("DELETE FROM proxy_groups WHERE id = :groupId")
        fun deleteById(groupId: Long): Int

        @Delete
        fun deleteGroup(group: ProxyGroup)

        @Delete
        fun deleteGroup(groupList: List<ProxyGroup>)

        @Insert
        fun createGroup(group: ProxyGroup): Long

        @Update
        fun updateGroup(group: ProxyGroup)

        @Query("DELETE FROM proxy_groups")
        fun reset()

        @Insert
        fun insert(groupList: List<ProxyGroup>)

    }

    companion object {
        @JvmField
        val CREATOR = object : Serializable.CREATOR<ProxyGroup>() {

            override fun newInstance(): ProxyGroup {
                return ProxyGroup()
            }

            override fun newArray(size: Int): Array<ProxyGroup?> {
                return arrayOfNulls(size)
            }
        }
    }

}