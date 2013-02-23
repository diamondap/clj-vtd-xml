(ns clj-vtd-xml.core
  "This is a Clojure wrapper around the Java VTD-XML parser, which provides
   very fast XML parsing."
  (:import java.util.Date)
  (:import java.io.ByteArrayInputStream)
  (:import [com.ximpleware VTDGen VTDNav AutoPilot])
  (:require [clojure.xml :as xml])
  (:require [clojure.string :as string]))


;; The functions navigator and find-indexes come from willtim's
;; gist at GitHub --> https://gist.github.com/822769
(defn navigator
  "Given an XML string, returns a VTDNav object. Param namespace-aware
   is a boolean indicating whether the navigator should take XML namespaces
   into account."
  [xml-string namespace-aware]
  {:pre [(instance? java.lang.String xml-string)
         (not (nil? xml-string))] }
  (let [xml-as-byte-array (. xml-string getBytes)
        gen (doto (VTDGen.)
                  (.setDoc xml-as-byte-array)
                  (.parse namespace-aware))]
        (.getNav gen)))

(defn- navigator-seq
  [navigator ap]
  (let [r (.evalXPath ap)]
    (if (= r -1)
      []
      (cons (.cloneNav navigator) (lazy-seq (navigator-seq navigator ap))))))


(defn find-indexes
  "Returns a lazy seq of VTDNav objects, one for each element in the XML
   doc that matches the specified xpath. VTDNav objects are merely pointers
   to positions within the byte stream. You can pass each of these objects
   to the content function to extract the text content, or pass them to the
   the raw-xml function to retrieve the literal XML string."
  ([navigator xpath]
     (find-indexes navigator xpath nil))
  ([navigator xpath ns-hash]
     {:pre [(instance? com.ximpleware.VTDNav navigator)
            (string? xpath)
            (or (nil? ns-hash) (map? ns-hash))] }
     (let [nav-clone (.cloneNav navigator)
           ap (AutoPilot. nav-clone)]
       (if ns-hash
         (doseq [[ns-prefix url] ns-hash]
           (. ap declareXPathNameSpace ns-prefix url)))
       (. ap selectXPath xpath)
       (navigator-seq nav-clone ap))))



(defn to-parent!
  "Moves the VTDNav object's cursor to the parent of whatever element it's
   currently pointing to. This changes the navigator's internal pointer!"
  [navigator]
  {:pre [(instance? com.ximpleware.VTDNav navigator)] }
  (. navigator toElement com.ximpleware.VTDNav/PARENT))


(defn to-first-child!
  "Moves the VTDNav object's cursor to the first child of whatever element
   it's currently pointing to. This changes the navigator's internal pointer!"
  ([navigator]
     {:pre [(instance? com.ximpleware.VTDNav navigator)] }
     (. navigator toElement com.ximpleware.VTDNav/FIRST_CHILD))
  ([navigator element-name]
     {:pre [(instance? com.ximpleware.VTDNav navigator)
            (not (clojure.string/blank? element-name))] }
     (. navigator toElement com.ximpleware.VTDNav/FIRST_CHILD element-name)))

(defn to-last-child!
  "Moves the VTDNav object's cursor to the last child of whatever element
   it's currently pointing to. This changes the navigator's internal pointer!"
  ([navigator]
     {:pre [(instance? com.ximpleware.VTDNav navigator)] }
     (. navigator toElement com.ximpleware.VTDNav/LAST_CHILD))
  ([navigator element-name]
     {:pre [(instance? com.ximpleware.VTDNav navigator)
            (not (clojure.string/blank? element-name))] }
     (. navigator toElement com.ximpleware.VTDNav/LAST_CHILD element-name)))


(defn to-next-sibling!
  "Moves the VTDNav object's cursor to the next sibling of whatever element
   it's currently pointing to. This changes the navigator's internal pointer!"
  ([navigator]
     {:pre [(instance? com.ximpleware.VTDNav navigator)] }
     (. navigator toElement com.ximpleware.VTDNav/NEXT_SIBLING))
  ([navigator element-name]
     {:pre [(instance? com.ximpleware.VTDNav navigator)
            (not (clojure.string/blank? element-name))] }
     (. navigator toElement com.ximpleware.VTDNav/NEXT_SIBLING element-name)))


(defn to-prev-sibling!
  "Moves the VTDNav object's cursor to the previous sibling of whatever
   element it's currently pointing to. This changes the navigator's internal
   pointer!"
  ([navigator]
     {:pre [(instance? com.ximpleware.VTDNav navigator)] }
     (. navigator toElement com.ximpleware.VTDNav/PREV_SIBLING))
  ([navigator element-name]
     {:pre [(instance? com.ximpleware.VTDNav navigator)
            (not (clojure.string/blank? element-name))] }
     (. navigator toElement com.ximpleware.VTDNav/PREV_SIBLING element-name)))


;; Split xpath expressions on this char to get attribute name
(def split-char #"@")


(defn attr-name
  "Returns the attribute name from an xpath expression. This is the text after
   the @ sign. Param xpath is a string. Returns the entire string if there is
   no @ sign, or if there's nothing after the @ sign. For example, if xpath
   is '/company/address/@city', this will return 'city'."
  [xpath]
  {:pre [(instance? java.lang.String xpath)
         (not (nil? xpath))] }
  (last (clojure.string/split xpath split-char)))


(defn position
  "Returns a hash with keys :offset and :length describing where in the byte
   stream the current element begins and how long it is."
  [navigator]
  {:pre [(instance? com.ximpleware.VTDNav navigator)] }
  (let [long-val (. navigator getElementFragment)]
    {
     :offset (. long-val intValue)
     :length (bit-shift-right long-val 32)
     }))


(defn is-within?
  "Returns true if the element that nav2 points to is inside of the element
   that nav1 is pointing to.

   Returns true if the navigator is pointing to an element whose start
   position is between start and end."
  ([nav1 nav2]
     {:pre [(instance? com.ximpleware.VTDNav nav1)
            (instance? com.ximpleware.VTDNav nav2)] }
     (let [nav1-position (position nav1)]
       (is-within? nav2
                   (:offset nav1-position)
                   (+ (:length nav1-position) (:offset nav1-position)))))
  ([navigator start end]
     {:pre [(instance? com.ximpleware.VTDNav navigator)
            (number? start)
            (number? end)] }
     (let [offset (:offset (position navigator))]
       (and (> offset start) (< offset end)))))


(defn token-type
  "Returns the type of token at the current navigator index. This is an integer
   which you can test against the following enumeration.

   0   com.ximpleware.VTDNav/TOKEN_STARTING_TAG
   1   com.ximpleware.VTDNav/TOKEN_ENDING_TAG
   2   com.ximpleware.VTDNav/TOKEN_ATTR_NAME
   3   com.ximpleware.VTDNav/TOKEN_ATTR_NS
   4   com.ximpleware.VTDNav/TOKEN_ATTR_VAL
   5   com.ximpleware.VTDNav/TOKEN_CHARACTER_DATA
   6   com.ximpleware.VTDNav/TOKEN_COMMENT
   7   com.ximpleware.VTDNav/TOKEN_PI_NAME
   8   com.ximpleware.VTDNav/TOKEN_PI_VAL
   9   com.ximpleware.VTDNav/TOKEN_DEC_ATTR_NAME
   10  com.ximpleware.VTDNav/TOKEN_DEC_ATTR_VAL
   11  com.ximpleware.VTDNav/TOKEN_CDATA_VAL
   12  com.ximpleware.VTDNav/TOKEN_DTD_VAL
   13  com.ximpleware.VTDNav/TOKEN_DOCUMENT
   "
  [navigator]
  {:pre [(instance? com.ximpleware.VTDNav navigator)] }
  (. navigator getTokenType (. navigator getCurrentIndex)))


(defn element?
  "Returns true if the navigator is pointing to an element."
  [navigator]
  {:pre [(instance? com.ximpleware.VTDNav navigator)] }
  (= com.ximpleware.VTDNav/TOKEN_STARTING_TAG (token-type navigator)))


(defn attribute?
  "Returns true if the navigator is pointing to an attribute."
  [navigator]
  {:pre [(instance? com.ximpleware.VTDNav navigator)] }
  (= com.ximpleware.VTDNav/TOKEN_ATTR_NAME (token-type navigator)))


(defn node-name
  "Returns the name of the element the navigator is currently pointing to."
  [navigator]
  {:pre [(instance? com.ximpleware.VTDNav navigator)] }
  (. navigator toString (. navigator getCurrentIndex)))


(defn raw-xml-string
  "Returns the raw xml element at the current navigator index. This means
   an XML snippet with start tag, end tag, and everything in between. Even if
   your xpath specified an attribute, you will get XML for the entire element
   that contains that attribute."
  [navigator]
  {:pre [(instance? com.ximpleware.VTDNav navigator)] }
  (let [position (position navigator)]
    (String. (. (. navigator getXML) getBytes
                (:offset position)
                (:length position)))))


(defn raw-xml-stream
  "Returns a ByteArrayInputStream of the xml element at the current navigator
   index. This means an XML snippet with start tag, end tag, and everything
   in between. This stream is suitable for feeding to a SAX reader, or to the
   get-hash function. Note that if your xpath specifies an attribute, you will
   get the entire element that is the parent of that attribute."
  [navigator]
  {:pre [(instance? com.ximpleware.VTDNav navigator)] }
  (let [position (position navigator)]
    (ByteArrayInputStream. (. (. navigator getXML) getBytes
                              (:offset position)
                              (:length position)))))


(defn element-text
  "Returns the text content of the element that the navigator is currently
   pointing to. Assumes that navigator is not nil, and is pointing to an
   element."
  [navigator]
  {:pre [(instance? com.ximpleware.VTDNav navigator)] }
  (try
    (let [element-text (. navigator getText)]
      (if (nil? element-text)
        nil
        (. navigator toNormalizedString element-text)))
    (catch Exception ex
      (throw (UnsupportedOperationException.
             (str "Function element-text cannot return text "
                  "from an element that contains other elements. "
                  "This function supports elements that contain "
                  "text only. Raw XML of the offending element follows: \n"
                  (raw-xml-string navigator)))))))


(defn attribute-text
  "Returns the text content of the attribute that the navigator is currently
   pointing to. Assumes that navigator is not nil, and is pointing to an
   element."
  [navigator attribute-name]
  {:pre [(instance? com.ximpleware.VTDNav navigator)
         (instance? java.lang.String attribute-name)
         (not (clojure.string/blank? attribute-name))] }
  (let [attr-value (. navigator getAttrVal attribute-name)]
    (if (nil? attr-value)
      nil
      (. navigator toNormalizedString attr-value))))


(defn- text-at
  "Extracts the text value from the specified XPATH."
  [navigator xpath]
  {:pre [(instance? com.ximpleware.VTDNav navigator)
         (instance? java.lang.String xpath)
         (not (clojure.string/blank? xpath))] }
  (if (attribute? navigator)
    (attribute-text navigator (attr-name xpath))
    (element-text navigator)))


(defn first-match
  "Returns the text content of the first node within the navigator that
   matches the specified XPATH expression. The only nodes that will be
   searched are the node to which the navigator is currently pointing and
   its descendants. Use a relative XPATH expression!"
  ([navigator xpath]
     (first-match navigator xpath nil))
  ([navigator xpath ns-hash]
     {:pre [(instance? com.ximpleware.VTDNav navigator)
            (string? xpath)
            (not (clojure.string/blank? xpath))
            (or (nil? ns-hash) (map? ns-hash))] }
     (let [nav (first (find-indexes navigator xpath ns-hash))]
       (if-not (nil? nav)
         (text-at nav xpath)))))


(defn all-matches
  "Returns a lazy seq of the content of all nodes matching the specified XPATH.
   The only nodes that will be searched are the node to which the navigator
   is currently pointing and its descendants. Use a relative XPATH expression!"
  ([navigator xpath]
     (all-matches navigator xpath nil))
  ([navigator xpath ns-hash]
     {:pre [(instance? com.ximpleware.VTDNav navigator)
            (string? xpath)
            (not (clojure.string/blank? xpath))
            (or (nil? ns-hash) (map? ns-hash))] }
  (map #(text-at % xpath) (find-indexes navigator xpath ns-hash))))


(defn get-xml
  "Returns a lazy seq of the literal XML of all nodes matching the specified
   xpath. Each item in the seq is a string. Param nav is a VTDNav object,
   which you can create with the navigator function."
  ([navigator xpath]
     (get-xml navigator xpath nil))
  ([navigator xpath ns-hash]
     {:pre [(instance? com.ximpleware.VTDNav navigator)
            (string? xpath)
            (not (clojure.string/blank? xpath))
            (or (nil? ns-hash) (map? ns-hash))] }
    (map raw-xml-string (find-indexes navigator xpath ns-hash))))


(defn get-hash
  "Converts the XML element(s) at the specified xpath to a seq of hashes,
   using a fast SAX parser. The seq will contain one hash for each element
   that matched the xpath expression.

   The xpath expression should return an element, not an attribute,
   because the SAX parser is expecting XML markup.

   If your xpath leads to an attribute, use (all-matches nav xpath) to
   retrieve the value.

   Some text values within the hash may have leading, trailing, and embedded
   spaces. You can call (normalize-text str) to get rid of these.

   This function uses clojure.xml/parse, so an XML node like this:

   <Employee role=\"Elected Member\">
     <Name>John McCain</Name>
   </Employee>

   Will yield a hash structure like this:

   {:tag   :Employee,
    :attrs {:role \"Elected Member\"},
    :content [{:tag :Name,
               :attrs nil,
               :content [\"John McCain\"]}]}
"
  ([navigator xpath]
     (get-hash navigator xpath nil))
  ([navigator xpath ns-hash]
     {:pre [(instance? com.ximpleware.VTDNav navigator)
            (string? xpath)
            (not (clojure.string/blank? xpath))
            (or (nil? ns-hash) (map? ns-hash))] }
     (let [xml-streams
           (map raw-xml-stream (find-indexes navigator xpath ns-hash))]
       (map xml/parse xml-streams))))

(def re-spaces #"\s+")

(defn normalize-text
  "Removes leading and trailing spaces. Collapses multiple spaces within
   the string to a single space."
  [string]
  (if (nil? string)
    nil
    (clojure.string/replace (clojure.string/trim string) re-spaces " ")))


