(ns revuecinema.service
  (:import
   [java.time LocalDate LocalTime LocalDateTime])
  (:require
   [clojure.data.json :as json]
   [clojure.pprint :refer [pprint]])
  (:require
   [io.pedestal.http :as bootstrap]
   [io.pedestal.http.route :as route]
   [io.pedestal.http.body-params :as body-params]
   [io.pedestal.http.route.definition :refer [defroutes expand-routes]]
   [io.pedestal.interceptor.helpers :as interceptor
    :refer [defhandler defon-request defbefore definterceptor handler]]
   [io.pedestal.log :as log]
   [ring.util.response :as ring-resp])
  (:require
   [revuecinema.fetch :as fetch]))

;; NB: Pedestal 0.4.0 is introducing a new Interceptor API:
;;   https://github.com/pedestal/pedestal/pull/301
;; that should be used in order to avoid issues like:
;;   https://github.com/pedestal/pedestal/issues/308
;; In particular, the new API is required when performing AOT compilation.
;;
;; The gist of the new API is that it should be used in preference to
;; the old interceptor macros, which had issues when generating correct
;; interceptor classes at compile time (for AOT).

;; Cf. Pedestal body-params interceptor for JSON/transit parsing
;; support.
(defn to-json
  [show-seq]
  ;; Turn :date (LocalDate), :time (LocalTime),
  ;; and :date-time (LocalDateTime) instances into strings to render as
  ;; JSON.
  (let [stringify-dates
        (fn [show]
          (-> show
              (update-in [:date] #(.toString %))
              (update-in [:time] #(.toString %))
              (update-in [:date-time] #(.toString %))))]
    (json/write-str (map stringify-dates show-seq))))

(defn movies-for-date
  [movie-seq ^LocalDate d]
  (filterv #(= (:date %) d) movie-seq))

(def movies-page
  (interceptor/handler
   ::movies-page
   (fn [request]
     (log/info "in movies-page")
     ;; Pull the movies data from request after it was put there by an
     ;; interceptor.
     (let [movies (get request :movies)]
       (ring-resp/response movies)))))

(def root-page
  (interceptor/handler
   ::root-page
   (fn [request]
     ;; TODO return a HATEOAS document
     (ring-resp/response "root"))))

(def movies-for-date-page
  (interceptor/handler
   (fn [request]
     (if-let [date-str (get-in request [:path-params :date])]
       (let [date (if (= "today" date-str)
                    (LocalDate/now)
                    (LocalDate/parse date-str))
             movies-seq (get request :movies)
             movies (movies-for-date movies-seq date)]
         (ring-resp/response movies))))))

(defroutes routes
  [[["/" {:get root-page}
     ["/movies" ^:interceptors [fetch/movies]
      {:get movies-page}
      ["/:date" ^:constraints {:date #"today|\d{4}-\d{2}-\d{2}"}
       {:get movies-for-date-page}]
      ]]]])

;; Consumed by revuecinema.server/create-server
;; See bootstrap/default-interceptors for additional options you can configure
(def service {:env :prod
              ;; You can bring your own non-default interceptors. Make
              ;; sure you include routing and set it up right for
              ;; dev-mode. If you do, many other keys for configuring
              ;; default interceptors will be ignored.
              ;; ::bootstrap/interceptors []
              ::bootstrap/routes routes

              ;; The default router will be the new prefix-tree router,
              ;; but the original linear router is still available for
              ;; use. The service map can now take a ::router option,
              ;; which can be a keyword (:prefix-tree or :linear-search)
              ;; or a function (your own custom router).
              ::bootstrap/router :prefix-tree

              ;; Uncomment next line to enable CORS support, add
              ;; string(s) specifying scheme, host and port for
              ;; allowed source(s):
              ;;
              ;; "http://localhost:8080"
              ;;
              ;;::bootstrap/allowed-origins ["scheme://host:port"]

              ;; Root for resource interceptor that is available by default.
              ::bootstrap/resource-path "/public"

              ::bootstrap/type :jetty
              ::bootstrap/host "0.0.0.0"
              ::bootstrap/port 8080})
