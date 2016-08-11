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
* The number of PoolArena is defined by min of [available direct memory / chunksize / 2 / 3] | [2 \* cores]
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
± % for run in {1..10000}                                                                                                                                                                             !10594
do
  curl -X PUT --data-binary "@100KB.file" 127.0.0.1:3000
done
```

Check the PoolArena metrics

```clojure
...
...
"complete: 1 msgs, 102400 bytes"
"complete: 1 msgs, 102400 bytes"
"complete: 1 msgs, 102400 bytes"
"complete: 1 msgs, 102400 bytes"
"complete: 1 msgs, 102400 bytes"

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

You can see that each Arena only has a single chunk, with very little memory consumed

My assumption is the 1% consumption represents buffers that are not deallocated on release, rather they are cached in the ThreadLocal for later re-use.

Regardless if you send 10000 or 1M <100KB messages, the PoolArena metrics will look similar to above. Nice and orderly, no accumulation of chunks, very little memory consumed.

Closing that REPL and starting afresh with another, sending 20 20MB msgs:

```bash
± % for run in {1..20}                                                                                                                                                                               !10614
do
  curl -X PUT --data-binary "@20MB.file" 127.0.0.1:3000
done
```

Checking the PoolArena metrics we can see now that we have three PoolChunk in each arena.

At this point all the buffers have been released, but again, I think some are cached and that's where the % usage comes from. Strangely one Chunk has 0% usage and 0 bytes consumed.

Our msgs are not sent in parallel, so I think the multiple chunk allocation is caused by the interplay between small individual CompositeByteBuffer component buffers being allocated and the occasional large consolidated buffer.

```clojure
...
...
"complete: 1 msgs, 20971520 bytes"
"complete: 1 msgs, 20971520 bytes"
"complete: 1 msgs, 20971520 bytes"
"complete: 1 msgs, 20971520 bytes"

(.directArenas (PooledByteBufAllocator/DEFAULT))
=>
[#object[io.netty.buffer.PoolArena$DirectArena
         0x2459350d
         "Chunk(s) at 0~25%:
          none
          Chunk(s) at 0~50%:
          Chunk(1280c322: 15%, 2351104/16777216)
          Chunk(2f5493c3: 1%, 32768/16777216)
          Chunk(s) at 25~75%:
          none
          Chunk(s) at 50~100%:
          none
          Chunk(s) at 75~100%:
          Chunk(122122e7: 0%, 0/16777216)
          Chunk(s) at 100%:
          none
          tiny subpages:
          16: (2049: 1/32, offset: 8192, length: 8192, elemSize: 256)
          small subpages:
          "]
 #object[io.netty.buffer.PoolArena$DirectArena
         0x5eb87aed
         "Chunk(s) at 0~25%:
          none
          Chunk(s) at 0~50%:
          Chunk(30d47b77: 1%, 131072/16777216)
          Chunk(3b953c25: 1%, 98304/16777216)
          Chunk(203807aa: 13%, 2154496/16777216)
          Chunk(s) at 25~75%:
          none
          Chunk(s) at 50~100%:
          none
          Chunk(s) at 75~100%:
          Chunk(4d24affd: 0%, 0/16777216)
          Chunk(s) at 100%:
          none
          tiny subpages:
          16: (2049: 1/32, offset: 8192, length: 8192, elemSize: 256)
          small subpages:
          "]]
```

Sending another 20 large messages shows further PoolChunk fragmentation, now we have 14 across 2 PoolArenas, and none will be deallocated since the all have some % utilization.

```clojure
(.directArenas (PooledByteBufAllocator/DEFAULT))
=>
[#object[io.netty.buffer.PoolArena$DirectArena
         0x59df51b3
         "Chunk(s) at 0~25%:
          none
          Chunk(s) at 0~50%:
          Chunk(4f8456b6: 1%, 32768/16777216)
          Chunk(39480577: 1%, 131072/16777216)
          Chunk(490ef4f6: 2%, 229376/16777216)
          Chunk(21092c0a: 1%, 98304/16777216)
          Chunk(2a9cc7fa: 12%, 1892352/16777216)
          Chunk(s) at 25~75%:
          none
          Chunk(s) at 50~100%:
          none
          Chunk(s) at 75~100%:
          Chunk(4c55ef7b: 0%, 0/16777216)
          Chunk(s) at 100%:
          none
          tiny subpages:
          16: (2049: 1/32, offset: 8192, length: 8192, elemSize: 256)
          small subpages:
          "]
 #object[io.netty.buffer.PoolArena$DirectArena
         0x6e3fb277
         "Chunk(s) at 0~25%:
          none
          Chunk(s) at 0~50%:
          Chunk(354f283b: 1%, 65536/16777216)
          Chunk(b58d320: 2%, 262144/16777216)
          Chunk(63d9c73d: 1%, 131072/16777216)
          Chunk(94c20a: 1%, 98304/16777216)
          Chunk(4c562cd3: 1%, 98304/16777216)
          Chunk(15a37aa: 6%, 843776/16777216)
          Chunk(2c97e893: 6%, 884736/16777216)
          Chunk(s) at 25~75%:
          none
          Chunk(s) at 50~100%:
          none
          Chunk(s) at 75~100%:
          Chunk(a35eb99: 0%, 0/16777216)
          Chunk(s) at 100%:
          none
          tiny subpages:
          16: (2049: 1/32, offset: 8192, length: 8192, elemSize: 256)
          small subpages:
          "]]
```

At this point we have sent 40 large 20MB messages in serial to the test service. They have all been released.

We have 14 PoolChunk allocated to PoolArena at a cost of 16MB each, ~ 234MB in total, close to 100% available direct memory.

Sending one more 20MB causes another PoolChunk to be allocated, ~250MB of our 256MB limitation consumed by PoolChunk allocated to PoolArena.

Each 20MB messages also requires a hugeAllocation of the final consolidated ByteBuffer, normally this is supported by an Unpooled PoolChunk that is immediately deallocated on release and never associated to a PoolArena, however at this point we have no direct memory available to allocate this unpooled PoolChunk and we end up with a direct memory OOM at that attempted allocation.

Given that:
* None of the existing 15 PoolChunk across 2 PoolArenas are ever deallocated, since they all have low % usage
* Large messages often trigger a new PoolChunk allocation or 'huge' unpooled PoolChunk allocation
* There is no more capacity for allocating new PoolChunk of either variety

At this point we observe the symptom of intermittent OOM since small messages are handled perfectly well by the existing pool chunks, but anything big enough to trigger a new allocation fails.

Granted, you might not expect a service contrained to 256MB of direct memory to support 20MB messages. My production servers have 2GB. This reproducer isn't intended to be perfectly representative, in my case the issue with memory consumption is as much related to slow peers / backpressure as large messages. However I am interested to understand how this example demonstrates an ever increasing number of PoolChunk that are never deallocated, as that ever (even slowly) increasing consumption of memory space appears to be the key to my actual issue.

## License

Copyright © 2016 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
