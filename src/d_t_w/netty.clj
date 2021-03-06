(ns d-t-w.netty
  (:import [java.net InetSocketAddress]
           [io.netty.bootstrap ServerBootstrap]
           [io.netty.buffer PooledByteBufAllocator]
           [io.netty.channel.nio NioEventLoopGroup]
           [io.netty.channel ChannelInitializer ChannelHandler ChannelHandlerContext SimpleChannelInboundHandler ChannelFutureListener ChannelOption]
           [io.netty.channel.socket SocketChannel]
           [io.netty.channel.socket.nio NioServerSocketChannel]
           [io.netty.handler.codec.http DefaultHttpResponse HttpVersion HttpResponseStatus HttpServerCodec LastHttpContent HttpContent HttpObjectAggregator FullHttpMessage]))

(defn ->dropping-handler
  []
  (let [request-size (atom 0)
        chunks       (atom 0)]
    (proxy [SimpleChannelInboundHandler] []
      (channelRead0 [^ChannelHandlerContext ctx msg]
        (when (instance? HttpContent msg)
          (swap! request-size #(+ % (.readableBytes (.content msg))))
          (swap! chunks inc))
        (when (instance? LastHttpContent msg)               ;; includes FullHttpMessage (output by HttpObjectAggregator)
          (-> (.writeAndFlush ctx (DefaultHttpResponse. HttpVersion/HTTP_1_1 HttpResponseStatus/OK))
              (.addListener ChannelFutureListener/CLOSE))
          (prn (str "complete: " @chunks " msgs, " @request-size " bytes"))))
      (exceptionCaught [^ChannelHandlerContext ctx thr]
        (.printStackTrace thr)
        (-> (.writeAndFlush ctx (DefaultHttpResponse. HttpVersion/HTTP_1_1 HttpResponseStatus/INTERNAL_SERVER_ERROR))
            (.addListener ChannelFutureListener/CLOSE)))
      (isSharable [] true))))

(defn start!
  ([]
   (start! true))
  ([aggregate?]
   (-> (ServerBootstrap.)
       (.group (NioEventLoopGroup. 2))
       (.channel NioServerSocketChannel)
       (.localAddress (InetSocketAddress. (int 3000)))
       (.childOption ChannelOption/ALLOCATOR PooledByteBufAllocator/DEFAULT)
       (.childHandler (proxy [ChannelInitializer] []
                        (initChannel [^SocketChannel ch]
                          (-> (.pipeline ch)
                              (.addLast (into-array
                                          ChannelHandler
                                          (if aggregate?
                                            [(HttpServerCodec. 4098 8196 8196)
                                             (HttpObjectAggregator. Integer/MAX_VALUE)
                                             (->dropping-handler)]
                                            [(HttpServerCodec. 4098 8196 8196)
                                             (->dropping-handler)])))))))
       (.bind)
       (.sync))
   (prn "Server started, port 3000")))
