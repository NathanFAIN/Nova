package xyz.xenondevs.nova.data.world

import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import xyz.xenondevs.nova.util.data.append
import xyz.xenondevs.nova.util.data.readStringList
import xyz.xenondevs.nova.util.data.toByteArray
import xyz.xenondevs.nova.util.data.writeStringList
import xyz.xenondevs.nova.util.getOrSet
import xyz.xenondevs.nova.world.ChunkPos
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set

private fun ByteBuf.readBooleanArray(size: Int): BooleanArray {
    val booleans = BooleanArray(size)
    val bytes = ByteArray(size / 8).also { readBytes(it) }
    for (i in bytes.indices) {
        val byte = bytes[i]
        repeat(8) { booleans[i * 8 + it] = byte.toInt() shr (7 - it) and 1 == 1 }
    }
    
    return booleans
}

private fun ByteBuf.writeBooleanArray(booleans: BooleanArray) {
    val bytes = ByteArray(booleans.size / 8)
    for (i in booleans.indices) {
        val bit = if (booleans[i]) 1 else 0
        bytes[i / 8] = (bytes[i / 8].toInt() shl 1 or bit).toByte()
    }
    writeBytes(bytes)
}

/**
 * A binary region file.
 *
 * Format:
 * ```
 * RegionFile {
 *      int8     version;
 *      int32    type_pool_size;
 *      int32    type_pool_count;
 *      string[] type_pool[type_pool_count];
 *      int32    header_size;
 *      chunk_positions[header_size / 12]; {
 *          int32       packed_world_pos;
 *          Int64       pos;
 *      }
 *      chunk_data[n]; {
 *          int32      size;
 *          int16      packed_rel_pos;
 *          int32      type_pool_index;
 *          blockState state;
 *      }
 * }
 * ```
 * TODO
 * - remove unused types from pool
 * - delete empty chunks
 * - Instead of storing chunk size, get distance to next chunk in file
 */
class RegionFile(val world: WorldDataStorage, val file: File, val regionX: Int, val regionZ: Int) {
    
    val chunks = arrayOfNulls<RegionChunk?>(1024)
    
    /**
     * Position of chunk data in the file
     */
    val chunkPositions = LinkedHashMap<Int, AtomicLong>()
    val typePool = ArrayList<String>()
    
    val raf = RandomAccessFile(file, "rw")
    
    fun init() {
        if (raf.length() == 0L) {
            raf.setLength(0L)
            raf.writeByte(0) // File format version
            raf.writeInt(4) // Type pool size (including count)
            raf.writeInt(0) // Type pool count
            raf.writeInt(0) // Header size
        } else {
            raf.seek(0)
            if (raf.readByte().toInt() != 0)
                throw IllegalStateException(file.absolutePath + " is not a valid region file")
            readHeader()
        }
    }
    
    //<editor-fold desc="Writing">
    
    fun save(chunk: RegionChunk) {
        val packedCoords = chunk.packedCoords
        var pos = chunkPositions[packedCoords.toInt()]?.get()
        if (pos != null) {
            raf.seek(pos)
            val length = raf.readInt()
            val buf = Unpooled.buffer()
            val poolChanged = chunk.write(buf, typePool)
            val newData = buf.toByteArray()
            val newLength = newData.size
            raf.seek(pos)
            raf.writeInt(newLength)
            raf.append(pos + 4, pos + 4 + length, newData)
            adjustHeader(pos, (newLength - length).toLong(), poolChanged)
        } else {
            raf.seek(raf.length())
            pos = raf.filePointer
            val buf = Unpooled.buffer()
            val poolChanged = chunk.write(buf, typePool)
            val newData = buf.toByteArray()
            chunkPositions[packedCoords.toInt()] = AtomicLong(pos)
            writeHeader(rewritePool = poolChanged)
            raf.writeInt(newData.size)
            raf.write(newData)
        }
    }
    
    private fun adjustHeader(from: Long, offset: Long, rewritePool: Boolean = false) {
        chunkPositions.asSequence()
            .filter { it.value.get() > from }
            .forEach { (_, idx) -> idx.addAndGet(offset) }
        val pos = chunkPositions.values.firstOrNull { it.get() > 10000 }
        writeHeader(rewritePool)
    }
    
    private fun writeHeader(rewritePool: Boolean = false) {
        raf.seek(1)
        val currentTypeLength = raf.readInt()
        raf.skipBytes(currentTypeLength)
        val pos = raf.filePointer
        val currentSize = raf.readInt()
        val newSize = chunkPositions.size * 12
        var delta = (newSize - currentSize).toLong()
        val out = Unpooled.buffer()
        if (rewritePool) {
            val poolBytesCount = out.writeStringList(typePool)
            raf.seek(1)
            raf.writeInt(poolBytesCount)
            delta += (poolBytesCount - currentTypeLength).toLong()
        }
        out.writeInt(newSize)
        chunkPositions.forEach { (packed, pos) ->
            out.writeInt(packed)
            out.writeLong(pos.addAndGet(delta))
        }
        raf.append(if (rewritePool) 5 else pos, pos + 4 + currentSize, out.toByteArray())
    }
    
    fun saveAll() {
        chunks.filterNotNull().forEach(::save)
    }
    
    //</editor-fold>
    
    //<editor-fold desc="Reading">
    
    private fun readHeader() {
        raf.seek(5)
        typePool.addAll(raf.readStringList())
        repeat(raf.readInt() / 12) {
            chunkPositions[raf.readInt()] = AtomicLong(raf.readLong())
        }
    }
    
    fun read(packedCoords: Int): RegionChunk {
        val chunk = RegionChunk(
            file = this,
            relChunkX = packedCoords shr 5,
            relChunkZ = packedCoords and 0x1F
        )
        val pos = chunkPositions[packedCoords]?.get() ?: return chunk
        raf.seek(pos)
        val bytes = ByteArray(raf.readInt())
        raf.read(bytes)
        chunk.read(Unpooled.wrappedBuffer(bytes), typePool)
        return chunk
    }
    
    //</editor-fold>
    
    fun getChunk(pos: ChunkPos): RegionChunk {
        val dx = pos.x and 0x1F
        val dz = pos.z and 0x1F
        val packedCoords = dx shl 5 or dz
        return chunks.getOrSet(packedCoords) { read(packedCoords) }
    }
    
    fun isAnyChunkLoaded(): Boolean {
        val minChunkX = regionX shl 5
        val minChunkZ = regionZ shl 5
        
        for (chunkX in minChunkX until (minChunkX + 32)) {
            for (chunkZ in minChunkZ until (minChunkZ + 32)) {
                if (world.world.isChunkLoaded(chunkX, chunkZ))
                    return true
            }
        }
        
        return false
    }
    
}
