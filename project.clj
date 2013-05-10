(defproject org.clojars.pauld/link "0.5.0-SNAPSHOT"
  :description
  "A straightforward (not-so-clojure) clojure wrapper for java nio framework.
   This fork provides tls support. Now with tls and server shutdown."
  :url "http://github.com/paulrd/link"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [io.netty/netty "3.6.1.Final"]
                 [commons-pool "1.6"]])
