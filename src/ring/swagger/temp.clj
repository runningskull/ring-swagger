(ns ring.swagger.temp
  (:require [schema.core :as s]
            [schema.macros :refer [validation-error]]
            [ring.swagger.json-schema :as sjson]
            [ring.swagger.swagger2 :as swag]))


(defn normalized-num? [x]
  (<= 0 x 1))

(def RaffleId #"\w+:\d+")
(def Normd (s/both s/Num (s/pred normalized-num?)))




;;;; Widget-specific Schemas

;; TODO: Normd
(s/defschema WidgetGalleryItemCrop
  {:top s/Num
   :left s/Num
   :width s/Num
   :height s/Num})

(s/defschema WidgetGalleryItem
  {:id s/Str
   :title s/Str
   :media-id (s/maybe s/Keyword)
   :crop (s/maybe WidgetGalleryItemCrop)})

(s/defschema WidgetGallery
  {:show s/Bool
   :slides [(s/maybe WidgetGalleryItem)]})

(s/defschema WidgetFooter
  {:show s/Bool
   :label (s/maybe s/Str)
   :url (s/maybe s/Str)
   :imgurl (s/maybe s/Str)})

(s/defschema WidgetVendorFB
  {:id s/Str
   ;; :sharing {:mutt s/Int
   ;;           :dog s/Str
   ;;           ;; :title (s/maybe s/Str)
   ;;           ;; :description (s/maybe s/Str)
   ;;           ;; :thumbnail-url (s/maybe s/Str)
   ;;           }
   :app-id (s/maybe s/Str)})

(s/defschema WidgetVendorCfg
  ;; these are optional keys instead of (s/maybe <type>) because
  ;; "vendor" stuff seems likely to be removed/replaced more frequently
  ;; than, say, width and height fields. But,if there's a good reason,
  ;; we could move them to (s/maybe <type>) like everything else
  {:facebook WidgetVendorFB

   ;; (s/optional-key :twitter)
   ;; {:sharing {:wat s/Int
   ;;            :message (s/maybe s/Str)
   ;;            :auto-link (s/maybe s/Bool)}}

   ;; (s/optional-key :google)
   ;; {:analytics-id s/Str}

   ;; forward compatibility.
   ;; TODO: will additional keys be allowed w/o the following?:
   ;; (s/optional-key s/Keyword) {s/Keyword s/Any} ;; this doesn't seem to work
   }) 

(s/defschema WidgetTriggers
  {:order [(s/maybe s/Keyword)]
   :locked [(s/maybe s/Keyword)]
   :show-terms (s/maybe s/Bool)})

(s/defschema Settings-Widget
  {:triggers WidgetTriggers
   :gallery WidgetGallery
   :footer WidgetFooter
   :vendor-cfg  WidgetVendorCfg
   :announce (s/maybe s/Bool)
   :language (s/maybe s/Str) ;; TODO: use an enum?
   })



;;;; Raffle-level Schemas

(s/defschema MediaItem
  {:type (s/enum :img :vid)
   :url s/Str
   (s/optional-key :size) {:width s/Int
                           :height s/Int}})

(s/defschema Prize
  {:count s/Int
   :name s/Str})

;; (s/defschema Timeline
;;   {:start Date
;;    :end Date})

(s/defschema TermsConfig
  {:shiner s/Int
   :sponsor {:name s/Str
             :email s/Str
             :address s/Str}
   :urls {:homepage s/Str
          :privacy s/Str
          :main s/Str}
   :eligible {:countries s/Str
              :min-age s/Str}
   :deadlines {:winner-announce s/Str
               :winner-claim s/Str}})

(s/defschema Terms
  {:config s/Int

   :terms2 {:sponsor {:name s/Str
                      :email s/Str
                      :address s/Str}
            :urls {:homepage s/Str
                   :privacy s/Str
                   :main s/Str}
            :eligible {:countries s/Str
                       :min-age s/Str}
            :deadlines {:winner-announce s/Str
                        :winner-claim s/Str}}

   :html (s/maybe String)})

(s/defschema Trigger
  {:score s/Int
   :type s/Str
   :daily s/Bool
   :args {s/Keyword s/Any}
   :collection {:auto s/Bool
                :widget s/Bool
                (s/optional-key :mobile) s/Bool
                (s/optional-key :kiosk) s/Bool
                (s/optional-key s/Keyword) s/Bool ;; forward compatibility
                }}) 




;;;; The main Raffle object

(s/defschema Raffle
  {:_id RaffleId
   :shortcode s/Str

   :alive s/Bool
   :username s/Str
   :nickname s/Str

   :timezone {:display-name s/Str ;; TODO: validate? use enum?
              :offset s/Num}

   ;; disregarding "score" & deletes, how many unique entries have been submitted?
   :submissions-count s/Int

   ;; this one counts the "score", and can decrease if user deletes entries.
   :entry-count s/Int

   ;; :timeline Timeline
   :triggers {s/Keyword Trigger}
   :prizes {s/Keyword Prize}
   :media {s/Keyword MediaItem}

   ;; :terms TermsConfig
   :mutt Terms
   :terms2 {:sponsor {:name s/Str
                      :email s/Str
                      :address s/Str}
            :urls {:homepage s/Str
                   :privacy s/Str
                   :main s/Str}
            :eligible {:countries s/Str
                       :min-age s/Str}
            :deadlines {:winner-announce s/Str
                        :winner-claim s/Str}}

   :settings {:widget (s/maybe Settings-Widget)}
   
   :_planf {s/Keyword s/Any} ;; TODO: more strict schema?
   :_created-at s/Num ;; TODO: strictly ensure is a millisecond timestamp? CLJS?

   :_refer s/Bool ;; TODO: is this needed?
   :_raffle_num s/Int ;; TODO: is this needed?
   })
