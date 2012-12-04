(ns link.tcp
  (:refer-clojure :exclude [send])
  (:use [link.core])
  (:use [link.codec :only [netty-encoder netty-decoder]])
<<<<<<< HEAD
  (:use [link.pool :only [pool]])
  (:import [java.net InetSocketAddress])
  (:import [java.util.concurrent Executors])
  (:import [java.nio.channels ClosedChannelException])
  (:import [org.jboss.netty.bootstrap
            ClientBootstrap
            ServerBootstrap])
  (:import [org.jboss.netty.channel
            Channels
            ChannelPipelineFactory
            Channel
            ChannelHandlerContext
            ChannelFuture])
  (:import [org.jboss.netty.channel.socket.nio
            NioServerSocketChannelFactory
            NioClientSocketChannelFactory])
  (:import [link.core ClientSocketChannel]))
=======
  (:import [java.net InetSocketAddress]
           [java.util.concurrent Executors]
           [java.nio.channels ClosedChannelException]
           [javax.net.ssl SSLContext]
           [org.jboss.netty.bootstrap ClientBootstrap ServerBootstrap]
           [org.jboss.netty.channel Channels ChannelPipelineFactory Channel
            ChannelHandlerContext ChannelFuture]
           [org.jboss.netty.channel.socket.nio
            NioServerSocketChannelFactory NioClientSocketChannelFactory]
           [org.jboss.netty.handler.ssl SslHandler]
           [link.core ClientSocketChannel]))
>>>>>>> tls

(defn- create-pipeline [& handlers]
  (reify ChannelPipelineFactory
    (getPipeline [this]
      (let [pipeline (Channels/pipeline)]
        (doseq [i (range (count handlers))]
          (.addLast pipeline (str "handler-" i) (nth handlers i)))
        pipeline))))

(defn get-ssl-handler [context client-mode?]
  (SslHandler. (doto (.createSSLEngine context)
                 (.setIssueHandshake true)
		 (.setUseClientMode client-mode?))))

(defn- start-tcp-server [port handler encoder decoder threaded?
                         ordered tcp-options ssl-context]
  (let [factory (NioServerSocketChannelFactory.
                 (Executors/newCachedThreadPool)
                 (Executors/newCachedThreadPool))
        bootstrap (ServerBootstrap. factory)
        handlers* (if-not threaded?
                    [encoder decoder handler]
                    [encoder decoder (threaded-handler ordered)
                     handler])
        handlers (if ssl-context
                   (concat [(get-ssl-handler ssl-context false)]
                           handlers*)
                   handlers*)
        pipeline (apply create-pipeline handlers)]
    (.setPipelineFactory bootstrap pipeline)
    (.setOptions bootstrap tcp-options)
    (.bind bootstrap (InetSocketAddress. port))))

(defn tcp-server [port handler
                  & {:keys [encoder decoder codec threaded?
                            ordered? tcp-options ssl-context]
                     :or {encoder nil
                          decoder nil
                          codec nil
                          threaded? false
                          ordered? true
                          tcp-options {}
                          ssl-context nil}}]
  (let [encoder (netty-encoder (or encoder codec))
        decoder (netty-decoder (or decoder codec))]
    (start-tcp-server port handler
                      encoder decoder
                      threaded?
                      ordered?
                      tcp-options
                      ssl-context)))

(defn tcp-client-factory [handler
                          & {:keys [encoder decoder codec tcp-options]
                             :or {tcp-options {}}}]
  (let [encoder (netty-encoder (or encoder codec))
        decoder (netty-decoder (or decoder codec))
        bootstrap (ClientBootstrap.
                   (NioClientSocketChannelFactory.
                    (Executors/newCachedThreadPool)
                    (Executors/newCachedThreadPool)))
        handlers [encoder decoder handler]
        pipeline (apply create-pipeline handlers)]
    (.setPipelineFactory bootstrap pipeline)
    (.setOptions bootstrap tcp-options)
    bootstrap))

(defn- connect [^ClientBootstrap bootstrap addr]
  (loop [chf (.. (.connect bootstrap addr)
                 awaitUninterruptibly)
         interval 5000]
    (if (.isSuccess ^ChannelFuture chf)
      (.getChannel ^ChannelFuture chf)
      (do
        (Thread/sleep interval)
        (recur (.. (.connect bootstrap addr)
                   awaitUninterruptibly)
               interval)))))

(defn tcp-client [^ClientBootstrap bootstrap host port
                  & {:keys [lazy-connect]
                     :or {lazy-connect false}}]
  (let [addr (InetSocketAddress. ^String host ^Integer port)]
    (let [connect-fn (fn [] (connect bootstrap addr))
          chref (agent (if-not lazy-connect (connect-fn)))]
      (ClientSocketChannel. chref connect-fn))))

(defn tcp-client-pool [^ClientBootstrap bootstrap host port
                       & {:keys [lazy-connect pool-options]
                          :or {lazy-connect false
                               pool-options {:max-active 8
                                             :exhausted-policy :block
                                             :max-wait -1}}}]
  (let [addr (InetSocketAddress. ^String host ^Integer port)
        maker (fn []
                (let [conn-fn (fn [] (connect bootstrap addr))]
                  (ClientSocketChannel.
                   (agent (if-not lazy-connect (conn-fn)))
                   conn-fn)))]
    (pool pool-options
          (makeObject [this] (maker))
          (destroyObject [this client] (close client))
          (validateObject [this client] (valid? client)))))



