(ns netty-direct.core
  (:import [java.net InetSocketAddress]
           [io.netty.bootstrap ServerBootstrap]
           [io.netty.buffer PooledByteBufAllocator]
           [io.netty.channel.nio NioEventLoopGroup]
           [io.netty.channel ChannelInitializer ChannelHandler ChannelHandlerContext SimpleChannelInboundHandler ChannelFutureListener ChannelOption]
           [io.netty.channel.socket SocketChannel]
           [io.netty.channel.socket.nio NioServerSocketChannel]
           [io.netty.handler.codec.http DefaultHttpResponse HttpVersion HttpResponseStatus HttpServerCodec LastHttpContent HttpContent]))

(defn ->dropping-handler
  []
  (let [request-size (atom 0)]
    (proxy [SimpleChannelInboundHandler] []
      (channelRead0
        [^ChannelHandlerContext ctx msg]
        (when (instance? HttpContent msg)
          (swap! request-size #(+ % (.readableBytes (.content msg)))))
        (when (instance? LastHttpContent msg)
          (-> (.writeAndFlush ctx (DefaultHttpResponse. HttpVersion/HTTP_1_1 HttpResponseStatus/OK))
              (.addListener ChannelFutureListener/CLOSE))
          (prn (str "request complete, total content: " @request-size " bytes"))))
      (exceptionCaught
        [^ChannelHandlerContext ctx ^Throwable cause]
        (.printStackTrace cause)
        (.close ctx))
      (isSharable [] true))))

(defn start!
  []
  (-> (ServerBootstrap.)
      (.group (NioEventLoopGroup.))
      (.channel NioServerSocketChannel)
      (.localAddress (InetSocketAddress. (int 8080)))
      (.childOption ChannelOption/ALLOCATOR PooledByteBufAllocator/DEFAULT)
      (.childHandler (proxy [ChannelInitializer] []
                       (initChannel
                         [^SocketChannel ch]
                         (-> ch
                             (.pipeline)
                             (.addLast (into-array
                                         ChannelHandler
                                         [(HttpServerCodec. 4098 8196 8196)
                                          (->dropping-handler)]))))))
      (.bind)
      (.sync))
  (prn "direct arenas at start:")
  (map str (.directArenas (PooledByteBufAllocator/DEFAULT))))