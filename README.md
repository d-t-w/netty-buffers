# netty-buffers

If Derek and Netty is a love story, ByteBuf pooling is the awkward period of confusion that hopefully passes.

*Goals*:

* Explore PooledByteBufAllocation of direct memory. Pooled heap is analogous but out of scope.
* Replicate behaviour where PoolChunk allocation fills available direct and further allocation OOMs.

*Brief*:

* ByteBuf pooling is optional, direct memory is preferred where available. Not suitable for all scenarios.
* Netty provides access to pooled memory via PoolArenas containing PoolChunks and Tiny/Small PoolSubpages (out of scope).
* PoolChunk default to 16MB, are allocated within a PoolArena lazily as required, deallocated when empty.
* The 

*Usage*:

Generate files of varying size:

```bash
dd if=/dev/zero of=100MB.file bs=1024k count=100   # 100MB
```

## License

Copyright © 2016 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
