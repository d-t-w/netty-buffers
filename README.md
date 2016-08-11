# netty-buffers

If Derek and Netty is a love story, ByteBuf pooling is the awkward period of confusion that hopefully passes

Netty version: 4.1.0-CR3 (mostly because that's what Aleph has as a dependency (scenario 2)

## Goals

* Explore PooledByteBufAllocation of direct memory. Pooled heap is analogous but out of scope
* Replicate behaviour where PoolChunk allocation fills available direct and further allocation OOMs

## Stretch Goal

* Turn this into a blog with some diagrams with that paper app, since you bought the pen.. 

## A Netty Pooled Memory Primer

* PooledByteBufAllocator is a jemalloc variant, introduced to counter GC pressure
* Pooling is optional, direct memory is preferred where available
* Pooled not suitable for all scenarios, unpooled heap sometimes better, unpooled direct probably never
* Netty provides access to pooled memory via PoolArenas containing PoolChunks and Tiny/Small PoolSubpages
* PoolChunk default to 16MB, are allocated within a PoolArena lazily as required, deallocated when empty
* The allocation of a PoolChunk to a PoolArena incurrs a consumption of chunksize (likely 16MB) memory 
* Number of PoolArena define by min of [available direct memory / chunksize / 2 / 3] | [2 \* cores]
* If each PoolArena has no more than 3 PoolChunks then memory consumption should stay under 50% 
* ThreadLocal cache of (some) recently released buf and arena means:
  * One event-loop thread only ever accesses one PoolArena 
  * Some buffers are not deallocated from the arena on release, ThreadLocal cached for later re-use
  * If you have fewer event-loop threads than PoolArena, some arena are not utilized 
* Tiny, Small, and Normal sized allocations are all handled differently
* Huge allocations (larger than chunk size) are allocated in a special Unpooled PoolChunk
* In normal operation with small messages it's hard to get more than 3 PoolChunk in one PoolArena
* Generally PoolChunk and buffer allocation / deallocation works fine 
* Given repeated small fixed size messages handled by one thread on one channel:
  * All allocation will be in the same PoolArena
  * Often only small number of buffers allocated since they are cached / re-used

## Scenario 1

 A long running netty HTTP service sporadically OOMs after several hundred days running in production

 Service supports a range of message size from 1k (often) -> 100MB (rare)

 Service uses standard Netty HTTP codes + HttpObjectAggregator

 Service transforms requests and writes to Cassandra, peer may be slow, conditions bursty

 This is not a buffer leak issue, it's a backpressure / slow-peers / pool usage issue

* HTTPObjectAggregator creates a CompositeByteBuffer with component buffers of 8192 bytes (default)
* CompositeByteBuffer consolidate at every 1024 component parts (default)
* Leads to large messages generating a series of small allocations and occasional 8MB and larger
* The 8MB+ allocations can lead to new PoolChunk being allocated when none existing have capacity
  * PoolChunk allocation likely with bursty large messages
  * PoolChunk allocation likely with slow peers (backpressure consideration)
* Can lead to PoolArenas having more than 3 PoolChunk allocated, approaching 100% memory consumption 
* Appears that some PoolChunk are not deallocated, not sure why but perhaps:
  * Some buffers are cached in the threadlocal, not deallocated (see a lot of PoolChunk w/ 1% usage)
  * Perhaps under constant load all PoolChunk are in use with new requests, not sure how chunks preferred
* Can lead to zero available memory for unpooled allocation even when many PoolChunks at 1% usage
* Larger aggregated buffers are allocated via allocateHuge which is unpooled
* OOM generally (95%) occurs at huge allocation, unpooled Chunk fails when existing pooled consume 100%   
* OOM occasionally (5%) occurs at point of new Chunk allocation when all memory consumed already
* Direct memory consumption confirmed by JVisualVM + Buffer Pools plugin

*Realisations*

* Buffer pooling probably not suited to this scenario of bursty, mixed message size, fine to turn it off
* Backpressure a real consideration, no way to avoid 100% memory consumption with no BP and slow peers
* Backpressure concern relates to pooled or unpooled, however:
  * Where PoolChunks grows and eventually consumes all available direct memory
  * If those PoolChunks are never deallocated (even though the initiating buffers have been released)
    * Can lead to a scenario where unpooled allocation (huge) or further PoolChunk allocation OOMs

*Questions*

* Why do some PoolChunk fail to deallocate / linger on 1% usage?

## Scenario 2

 Captured in this ticket related to Yada/Aleph: https://github.com/juxt/yada/issues/75

 Not related to my own issue, but I found the ticket when looking for other Pool/OOM issues

 It's Clojure, a couple of the Juxt guys are former colleagues, +ztellman, so why not try and help 


## Reproducing 

Start a REPL with max direct memory setting (to control the number of PoolArenas)

```bash
-XX:MaxDirectMemorySize=256M  # with default settings = two PoolArena
```

Confirm the direct pool arena metrics (arenas are grouped by utilisation %) 

```clojure
(.directArenas (PooledByteBufAllocator/DEFAULT))
=>
[#object[io.netty.buffer.PoolArena$DirectArena
         0x575d73cb
         "Chunk(s) at 0~25%:
          none
          Chunk(s) at 0~50%:
          none
          Chunk(s) at 25~75%:
          none
          Chunk(s) at 50~100%:
          none
          Chunk(s) at 75~100%:
          none
          Chunk(s) at 100%:
          none
          tiny subpages:
          small subpages:
          "]
 #object[io.netty.buffer.PoolArena$DirectArena
         0x11278407
         "Chunk(s) at 0~25%:
          none
          Chunk(s) at 0~50%:
          none
          Chunk(s) at 25~75%:
          none
          Chunk(s) at 50~100%:
          none
          Chunk(s) at 75~100%:
          none
          Chunk(s) at 100%:
          none
          tiny subpages:
          small subpages:
          "]]
```

You can see all the PoolArena have zero PoolChunks at this point.

Generate files of varying size:

```bash
dd if=/dev/zero of=100MB.file bs=1024k count=100   # 100MB
```

## Reproducing Scenario 1

A simple server configuaration (HttpCodec, HttpObjectAggregator, SimpleChannelHandler that drops msgs)

```clojure
(require '[d-t-w.netty :as netty])
=> nil
(netty/start!)
=> {:port 3000}
```

To verify expected 'normal' pool operation, send a series of 100k msgs to the server

```bash
± % for run in {1..1000}                                                                                                                                                                             !10594
do
  curl -X PUT --data-binary "@100KB.file" 127.0.0.1:3000
done
```

Check the PoolArena metrics

```clojure
...
...
"complete: 1 chunks, 102400 bytes"
"complete: 1 chunks, 102400 bytes"
"complete: 1 chunks, 102400 bytes"
"complete: 1 chunks, 102400 bytes"
"complete: 1 chunks, 102400 bytes"

(.directArenas (PooledByteBufAllocator/DEFAULT))
=>
[#object[io.netty.buffer.PoolArena$DirectArena
         0x4676f4ad
         "Chunk(s) at 0~25%:
          Chunk(662c6d27: 1%, 122880/16777216)
          Chunk(s) at 0~50%:
          none
          Chunk(s) at 25~75%:
          none
          Chunk(s) at 50~100%:
          none
          Chunk(s) at 75~100%:
          none
          Chunk(s) at 100%:
          none
          tiny subpages:
          16: (2049: 1/32, offset: 8192, length: 8192, elemSize: 256)
          small subpages:
          "]
 #object[io.netty.buffer.PoolArena$DirectArena
         0x5fb0e218
         "Chunk(s) at 0~25%:
          Chunk(2a4a6e9b: 1%, 122880/16777216)
          Chunk(s) at 0~50%:
          none
          Chunk(s) at 25~75%:
          none
          Chunk(s) at 50~100%:
          none
          Chunk(s) at 75~100%:
          none
          Chunk(s) at 100%:
          none
          tiny subpages:
          16: (2049: 1/32, offset: 8192, length: 8192, elemSize: 256)
          small subpages:
          "]]
```



## License

Copyright © 2016 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
