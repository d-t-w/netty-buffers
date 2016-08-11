# netty-buffers

If Derek and Netty is a love story, ByteBuf pooling is the awkward period of confusion that hopefully passes.

*Goals*:

* Explore PooledByteBufAllocation of direct memory. Pooled heap is analogous but out of scope.
* Replicate behaviour where PoolChunk allocation fills available direct and further allocation OOMs.

*A Netty Pooled Memory Primer*:

* PooledByteBufAllocator is a jemalloc variant, introduced to counter GC pressure
* Pooling is optional, direct memory is preferred where available. Not suitable for all scenarios
* Netty provides access to pooled memory via PoolArenas containing PoolChunks and Tiny/Small PoolSubpages
* PoolChunk default to 16MB, are allocated within a PoolArena lazily as required, deallocated when empty
* Number of PoolArena define by min of [available direct memory / chunksize / 2 / 3] | [2 \* cores]
* Means if each PoolArena has no more than 3 PoolChunks then memory consumption should stay under 50% 
* ThreadLocal cache of recently released buf and arena means:
** Each thread only ever accesses the same arena
** Some buffers are not deallocated from the arena on release, they're held in the cache for later use 

*Usage*:

Generate files of varying size:

```bash
dd if=/dev/zero of=100MB.file bs=1024k count=100   # 100MB
```

## License

Copyright © 2016 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
