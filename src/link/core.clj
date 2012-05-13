(ns link.core
  (:refer-clojure :exclude [send])
  (:import [clojure.lang IDeref])
  (:import [java.net InetSocketAddress])
  (:import [java.nio.channels ClosedChannelException])
  (:import [org.jboss.netty.channel
            Channel
            ChannelHandlerContext
            MessageEvent
            ExceptionEvent
            WriteCompletionEvent
            SimpleChannelUpstreamHandler])
  (:import [org.jboss.netty.handler.execution
            ExecutionHandler
            MemoryAwareThreadPoolExecutor
            OrderedMemoryAwareThreadPoolExecutor]))

(defprotocol MessageChannel
  (send [this msg])
  (channel-addr [this])
  (close [this]))

(deftype BlockingAtomReference [^Object lock atomic-ref
                                factory-fn validator-fn]
  IDeref
  (deref [this]
    (let [value @the-ref]
      (if (validator-fn value)
        value
        (do
          (locking lock
            (if (validator-fn @the-ref)
              @the-ref
              (do
                (loop [r (factory-fn)]
                  (if (:success r)
                    (swap! the-ref (:result r))
                    (recur (factory-fn))))))))))))

(defn batom [atomic-ref factory-fn validator-fn]
  (BlockingAtomReference. (object.)
                          atomic-ref
                          factory-fn
                          validator-fn))

(deftype ClientSocketChannel [ch-ref]
  MessageChannel
  (send [this msg]
    (let [ch @ch-ref]
     (.write ^Channel @ch msg)))
  (channel-addr [this]
    (.getLocalAddress ^Channel @ch-ref))
  (close [this]
    (.close ^Channel @ch-ref)))

(deftype SimpleWrappedSocketChannel [^Channel ch]
  MessageChannel
  (send [this msg]
    (.write ch msg))
  (channel-addr [this]
    (.getLocalAddress ch))
  (close [this]
    (.close ch)))

(defmacro ^{:private true} make-handler-macro [evt]
  (let [handler-name (str "on-" evt)
        symbol-name (symbol handler-name)
        args-vec-sym (symbol "args-vec")
        body-sym (symbol "body")]
    `(defmacro ~symbol-name [~args-vec-sym & ~body-sym]
       `{(keyword ~~handler-name) (fn ~~args-vec-sym ~@~body-sym)})))

(make-handler-macro open)
(make-handler-macro close)
(make-handler-macro message)
(make-handler-macro error)
(make-handler-macro connected)
(make-handler-macro disconnected)
(make-handler-macro write-complete)

(defmacro create-handler [& body]
  `(let [handlers# (merge ~@body)]
     (proxy [SimpleChannelUpstreamHandler] []
       (channelClosed [^ChannelHandlerContext ctx# e#]
         (if-let [handler# (:on-close handlers#)]
           (handler# (SimpleWrappedSocketChannel. (.getChannel ctx#)))
           (.sendUpstream ctx# e#)))
       (channelConnected [^ChannelHandlerContext ctx# e#]
         (if-let [handler# (:on-connected handlers#)]
           (handler# (SimpleWrappedSocketChannel. (.getChannel ctx#)))
           (.sendUpstream ctx# e#)))
       (channelDisconnected [^ChannelHandlerContext ctx# e#]
         (if-let [handler# (:on-disconnected handlers#)]
           (handler# (SimpleWrappedSocketChannel. (.getChannel ctx#)))
           (.sendUpstream ctx# e#)))
       (channelOpen [^ChannelHandlerContext ctx# e#]
         (if-let [handler# (:on-open handlers#)]
           (handler# (SimpleWrappedSocketChannel. (.getChannel ctx#)))
           (.sendUpstream ctx# e#)))
       
       (exceptionCaught [^ChannelHandlerContext ctx#
                         ^ExceptionEvent e#]
         (when-let [handler# (:on-error handlers#)]
           (let [ch# (SimpleWrappedSocketChannel. (.getChannel ctx#))
                 exp# (.getCause e#)]
             (handler# ch# exp#)))
         (.sendUpstream  ctx# e#))
       
       (messageReceived [^ChannelHandlerContext ctx#
                         ^MessageEvent e#]
         (when-let [handler# (:on-message handlers#)]
           (let [message# (.getMessage e#)
                 addr# (.getRemoteAddress e#)
                 ch# (SimpleWrappedSocketChannel. (.getChannel ctx#))]
             (handler# ch# message# addr#)))
         (.sendUpstream ctx# e#))

       (writeComplete [^ChannelHandlerContext ctx#
                       ^WriteCompletionEvent e#]
         (when-let [handler# (:on-write-complete handlers#)]
           (let [amount# (.getWrittenAmount e#)
                 ch# (SimpleWrappedSocketChannel. (.getChannel ctx#))]
             (handler# ch# amount#)))
         (.sendUpstream ctx# e#)))))

(defn threaded-handler [ordered]
  (let [core-size 20
        max-channel-memory 0 ;;unlimited
        max-total-memory 0 ;;unlimited
        ]
   (ExecutionHandler.
    (if ordered
      (OrderedMemoryAwareThreadPoolExecutor.
       core-size max-channel-memory max-total-memory)
      (MemoryAwareThreadPoolExecutor.
       core-size max-channel-memory max-total-memory)))))


