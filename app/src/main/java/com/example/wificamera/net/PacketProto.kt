package com.example.wificamera.net

import java.nio.ByteBuffer

/**
 * H.264 裸 UDP 分包协议。
 *
 * 每个 UDP 包结构：
 *   magic     2 字节  固定 0x55 0xAA
 *   flags     1 字节  bit0=帧起始分片, bit1=帧结束分片, bit2=SPS/PPS 配置帧
 *   frameId   4 字节  int，同一帧的所有分片相同
 *   totalSize 4 字节  int，整帧（H.264 NAL）字节数
 *   chunkIdx  2 字节  short，分片序号，从 0 开始
 *   payload   剩余字节
 */
object PacketProto {
    const val PORT = 8888
    const val MAGIC0: Byte = 0x55
    const val MAGIC1: Byte = 0xAA.toByte()

    const val FLAG_START = 0x01
    const val FLAG_END = 0x02
    const val FLAG_CONFIG = 0x04

    const val HEADER_SIZE = 13
    const val MAX_PAYLOAD = 1387 // 1387 + 13 = 1400，远低于常见 1500 MTU

    fun buildPacket(
        flags: Int,
        frameId: Int,
        totalSize: Int,
        chunkIdx: Int,
        payload: ByteArray,
        offset: Int,
        length: Int
    ): ByteArray {
        val packet = ByteArray(HEADER_SIZE + length)
        val buf = ByteBuffer.wrap(packet)
        buf.put(MAGIC0)
        buf.put(MAGIC1)
        buf.put(flags.toByte())
        buf.putInt(frameId)
        buf.putInt(totalSize)
        buf.putShort(chunkIdx.toShort())
        System.arraycopy(payload, offset, packet, HEADER_SIZE, length)
        return packet
    }

    /** 解析收到的数据包；magic 不匹配返回 null。 */
    fun parse(packet: ByteArray, length: Int): Parsed? {
        if (length < HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(packet, 0, length)
        if (buf.get() != MAGIC0 || buf.get() != MAGIC1) return null
        val flags = buf.get().toInt() and 0xFF
        val frameId = buf.getInt()
        val totalSize = buf.getInt()
        val chunkIdx = buf.getShort().toInt() and 0xFFFF
        val payload = ByteArray(length - HEADER_SIZE)
        System.arraycopy(packet, HEADER_SIZE, payload, 0, payload.size)
        return Parsed(flags, frameId, totalSize, chunkIdx, payload)
    }

    data class Parsed(
        val flags: Int,
        val frameId: Int,
        val totalSize: Int,
        val chunkIdx: Int,
        val payload: ByteArray
    )
}
