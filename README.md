# netty-buffers

If Derek and Netty is a love story, ByteBuf pooling is the awkward period of confusion that hopefully passes.

*Goals*:

* Explore PooledByteBufAllocation of direct memory. Pooled heap is analogous but out of scope.
* Replicate behaviour where PoolChunk allocation fills available direct and further allocation OOMs.

*Stretch Goal*

* Turn this into a blog with some diagrams with that paper app, since you bought the pen and all.

*A Netty Pooled Memory Primer*:

* PooledByteBufAllocator is a jemalloc variant, introduced to counter GC pressure
* Pooling is optional, direct memory is preferred where available. Not suitable for all scenarios
* Netty provides access to pooled memory via PoolArenas containing PoolChunks and Tiny/Small PoolSubpages
* PoolChunk default to 16MB, are allocated within a PoolArena lazily as required, deallocated when empty
* Number of PoolArena define by min of [available direct memory / chunksize / 2 / 3] | [2 \* cores]
* If each PoolArena has no more than 3 PoolChunks then memory consumption should stay under 50% 
* ThreadLocal cache of (some) recently released buf and arena means:
  * One thread only ever accesses the same PoolArena 
  * Some buffers are not deallocated from the arena on release, ThreadLocal cached for later re-use 
* Tiny, Small, and Normal sized allocations are all handled differently.
* Huge allocations (larger than chunk size) are allocated in a special Unpooled PoolChunk
* In normal operation with small messages it's hard to get more than 3 PoolChunk in one PoolArena
* Generally PoolChunk and buffer allocation / deallocation is reliable

Scenario:

 A long running netty HTTP service sporadically OOMs after several hundred days running in production

 Service supports a range of message size from 1k (often) -> 100MB (rare)

 Service uses standard Netty HTTP codes + HttpObjectAggregator

 Service transforms requests and writes to Cassandra, peer may be slow, conditions bursty.

* HTTPObjectAggregator creates a CompositeByteBuffer with component buffers of 8192 bytes (default)
* CompositeByteBuffer consolidate at every 1024 component parts (default)
* Leads to large messages generating a series of small allocations and occasional 8MB and larger
* Deallocation occurs asynchronously, a burst of large messages can lead to high allocation contention
* The 8MB large allocations can lead to new PoolChunk being allocated when none existing have capacity 
* Larger aggregated buffers are allocated via allocateHuge which is unpooled
* Can lead to PoolArenas having more than 3 PoolChunk allocated, approaching 100% use by all PoolChunks
* Appears that some PoolChunk are not deallocated, not sure why but perhaps:
  * Some buffers are cached in the threadlocal, not deallocated (see a lot of PoolChunk w/ 1% usage)
  * Perhaps under constant load all PoolChunk are in use with new requests, not sure how chunks preferred
* OOM generally (95%) occurs at huge allocation, unpooled Chunk fails when existing  pooled consume 100%   
* OOM occasionally (5%) occurs at point of new Chunk allocation when all memory consumed already
* Direct memory consumption confirmed by JVisualVM + Buffer Pools plugin

*Usage*:

Generate files of varying size:

```bash
dd if=/dev/zero of=100MB.file bs=1024k count=100   # 100MB
```

## License

Copyright © 2016 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
