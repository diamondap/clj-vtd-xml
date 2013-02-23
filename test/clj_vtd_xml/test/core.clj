(ns clj-vtd-xml.test.core
  (:use [clj-vtd-xml.core])
  (:use [clojure.test])
  (:import [java.io.File])
  (:require
   [clojure.pprint :as pp]
   [clojure.string :as string]))


(defn dirname
  "Given a file path, returns the directory that contains the file."
  [path]
  (.getParent (java.io.File. path)))

(defn expand-path
  "Given a relative path, returns an absolute path."
  [path]
  (.getCanonicalPath (java.io.File. path)))


(defn file-path
  "Returns absolute path the specified file in the same directory as
   this source file"
  [file-name]
  (str (expand-path (dirname (str "test/" *file*))) "/" file-name))


;; ----------------------------------------------------------------
;; Read XML files into strings. One file with namespaces, one
;; without.
;; ----------------------------------------------------------------
(def xml (slurp (file-path "sample.xml")))
(def xml-ns (slurp (file-path "sample_ns.xml")))


;; ----------------------------------------------------------------
;; This are the VTDNav object, which contains XML in the form of
;; a raw byte stream, plus an index of Virtual Token Descriptors.
;; ----------------------------------------------------------------
(def nav (navigator xml true))
(def nav-ns (navigator xml-ns true))


;; ----------------------------------------------------------------
;; The XML doc sample_ns.xml uses these two namespaces.
;; When clj-vtd-xml does namespace-aware XPATH processing, it
;; needs a hash mapping namespace prefixes to namespace URLs.
;; ----------------------------------------------------------------
(def ns-hash {"ns1" "http://www.skeema.kom/ns1"
              "ns2" "http://www.skeema.kom/ns2"})


;; ----------------------------------------------------------------
;; find-indexes should return a seq of VTDNav objects. Each object
;; points to an element that matches the specified XPATH.
;; ----------------------------------------------------------------
(deftest find-indexes-test
  (is (= 4 (count (find-indexes nav "/Directory/Organizations/Organization"))))
  (is (= 4 (count (find-indexes nav
                                (str "/Directory/Organizations"
                                     "/Organization"
                                     "/Employee[@role='Elected Member']")))))
  (is (= 12 (count (find-indexes nav "//Name"))))

  ;; Get a VTDNav object pointing to the first organization element.
  ;; When we run relative XPATH queries on this, it should return only
  ;; the self or descendants of this element.
  (let [first-org (first
                   (find-indexes nav "/Directory/Organizations/Organization"))]
    (is (= 3 (count (find-indexes first-org "Employee"))))
    (is (= 3 (count (find-indexes first-org "Employee/Name"))))
    (is (= 3 (count (find-indexes first-org "Employee//Name"))))))



;; ----------------------------------------------------------------
;; all-matches returns the content of whatever we find at the
;; specified xpath. The xpath must point to an attribute or to
;; a node that contains only text. It may not point to a complex
;; element that contains other elements.
;;
;; all-matches returns a seq of strings, not VTDNav objects
;;
;; When nav points to an element within the main document,
;; all-matches returns matches within that element.
;; ----------------------------------------------------------------
(deftest all-matches-test
  (is (= 12 (count (all-matches nav "//Name"))))
  (is (= "John Boehner" (first (all-matches nav "//Name"))))
  (is (= "Lady Gaga" (last (all-matches nav "//Name"))))
  ;; Run all-matches on elements within a single element.
  ;; In this case, the last organization element.
  (let [president (last
                   (find-indexes nav "/Directory/Organizations/Organization"))]
    (is (= 3 (count (all-matches president "Employee/Name"))))
    (is (= "Barack Obama" (first (all-matches
                                  president
                                  "Employee[@role='President']/Name"))))
    (is (= "Lady Gaga" (last (all-matches president "Employee/Name"))))))


(def senate-xpath
     "/Directory/Organizations/Organization[@name='US Senate']/Employee")

(def senate-xpath-ns
     (str "/Directory/ns1:Organizations"
          "/ns1:Organization[@name='US Senate']/ns2:Employee"))

;; ----------------------------------------------------------------
;; get-xml returns a seq containing the literal XML of all matching
;; nodes
;; ----------------------------------------------------------------
(deftest get-xml-test
  (is (= "<Employee role=\"Elected Member\">
        <Name>John McCain</Name>
      </Employee>"
         (first (get-xml nav senate-xpath))))
  (is (= 12 (count (get-xml nav "//Employee")))))




;; ----------------------------------------------------------------
;; get-hash returns a seq of XML elements matching the specified
;; XPATH. Each element comes back in the form of a hash with the
;; structure shown below. The raw XML of this structure appears
;; in the get-xml-test above.
;; ----------------------------------------------------------------
(deftest get-hash-test
 (is (= (first (get-hash nav senate-xpath))
        {:tag   :Employee,
         :attrs {:role "Elected Member"},
         :content [{:tag :Name,
                    :attrs nil,
                    :content ["John McCain"]}]}))
  (is (= 12 (count (get-hash nav "//Employee")))))


;; ----------------------------------------------------------------
;; normalize-text should remove all extraneous spaces from text.
;; ----------------------------------------------------------------
(deftest normalize-text-test
  (is (= "this is a string"
         (normalize-text "    this    is     a     string    "))))


;; ----------------------------------------------------------------
;; is-within? should return true if the second element is a
;; descendant of the first, or if an element's start position is
;; between start and end
;; ----------------------------------------------------------------
(deftest is-within-test
  (let [first-organization (first (find-indexes
                                   nav
                                   "/Directory/Organizations/Organization"))
        names (find-indexes nav "//Name")
        position (position first-organization)
        start (:offset position)
        end (+ start (:length position))]
   (is (is-within? first-organization (- start 1) (+ end 1)))
   (is (not (is-within? first-organization (+ start 1) (+ end 1))))
   (is (not (is-within? first-organization (- start 1) start)))
   (is (is-within? first-organization (first names)))
   (is (not (is-within? first-organization (last names))))))


;; ----------------------------------------------------------------
;; first-match should return the content of first node matching
;; the specified XPATH expression that is either 1) the node nav is
;; pointing to or 2) descendants of the node nav points to.
;;
;; Note that XPATH is relative to whatever node the nav is currently
;; pointing to.
;; ----------------------------------------------------------------
(deftest first-match-test
 (let [organizations (find-indexes nav "/Directory/Organizations/Organization")
       org1 (first organizations)
       org2 (nth organizations 1)]
   (is (= "John Boehner" (first-match org1 "Employee/Name")))
   (is (= "Willie Williams" (first-match org1 "Employee[@role='Staff']/Name")))
   (is (= "John McCain" (first-match org2 "Employee/Name")))
   (is (= "Cassandra Hadoop" (first-match org2 "Employee[@role='Staff']/Name")))))

;; ----------------------------------------------------------------
;; first-match should return nil, not throw exceptions, when given
;; an XPATH it can't find.
;; ----------------------------------------------------------------
(deftest first-match-no-match-test
  (let [organizations (find-indexes nav
                                    "/Directory/Organizations/Organization")
        org1 (first organizations)]
    (is (nil? (first-match org1 "dybbx/FAKE_ELEMENT")))
    (is (nil? (first-match org1 "wtwtwetsdgsh/@iioookkjj")))))


;; ----------------------------------------------------------------
;; all-matches should return an empty seq, not throw exceptions,
;; when given an XPATH it can't find.
;; ----------------------------------------------------------------
(deftest all-matches-no-match-test
  (let [organizations (find-indexes nav
                                    "/Directory/Organizations/Organization")
        org1 (first organizations)]
    (is (= () (all-matches org1 "dybbx/FAKE_ELEMENT")))
    (is (= () (all-matches org1 "wtwtwetsdgsh/@iioookkjj")))))


;; ----------------------------------------------------------------
;; node-name returns the name of the element or attribute that the
;; navigator currently points to
;; ----------------------------------------------------------------
(deftest node-name-test
  (let [organizations (find-indexes nav
                                    "/Directory/Organizations/Organization")
        org1 (first organizations)
        attr (first (find-indexes org1 "@name"))]
   (is (= "Organization" (node-name org1)))
   (is (= "name" (node-name attr)))))

;; ----------------------------------------------------------------
;; attribute? returns true when the VTD nav is pointing to
;; an attribute.
;; ----------------------------------------------------------------
(deftest attribute-test
 (let [organizations (find-indexes nav
                                    "/Directory/Organizations/Organization")
        org1 (first organizations)
        attr (first (find-indexes org1 "@name"))]
   (is (not (attribute? org1))
   (is (attribute? attr)))))

;; ----------------------------------------------------------------
;; element? correctly indictes when nav object is pointing to
;; an element.
;; ----------------------------------------------------------------
(deftest element-test
  (let [organizations (find-indexes nav
                                    "/Directory/Organizations/Organization")
        org1 (first organizations)
        attr (first (find-indexes org1 "@name"))]
    (is (element? org1))
    (is (not (element? attr)))))


;; ----------------------------------------------------------------
;; to-parent! moves the nav pointer to the parent of the current
;; element. It returns true on success, false on failure.
;; Note that all of these to-xxx functions alter the state of
;; VTDNav pointer!
;; ----------------------------------------------------------------
(deftest to-parent-test
 (let [organizations (find-indexes nav
                                   "/Directory/Organizations/Organization")
       org1 (first organizations)]
   ;; Make sure we're on Organization before moving up
   (is (= "Organization" (node-name org1)))
   (is (to-parent! org1)) ;; returns true on success
   (is (= "Organizations" (node-name org1)))
   (is (to-parent! org1))
   (is (= "Directory" (node-name org1)))))


;; ----------------------------------------------------------------
;; to-first-child! with name param goes to the first child having
;; the specified name
;; ----------------------------------------------------------------
(deftest to-first-child-with-name-test
  (let [organizations (find-indexes nav
                                   "/Directory/Organizations/Organization")
        org1 (first organizations)]
   (is (to-first-child! org1 "Employee")) ;; returns true on success
   (is (= "John Boehner" (first-match org1 "Name")))))


;; ----------------------------------------------------------------
;; to-first-child! with no name goes to the first child
;; ----------------------------------------------------------------
(deftest to-first-child-without-name-test
  (let [organizations (find-indexes nav
                                    "/Directory/Organizations/Organization")
       org1 (first organizations)]
   (is (to-first-child! org1)) ;; returns true on success
   (is (= "Location" (node-name org1)))))


;; ----------------------------------------------------------------
;; to-first-child! returns false if the requested node does
;; not exist
;; ----------------------------------------------------------------
(deftest to-first-child-return-test
  (let [org (first(find-indexes nav "/Directory/Organizations/Organization"))]
    (is (not (to-first-child! org "NonExistantNode")))
    (is (not (to-first-child! org "@NonExistantAttr")))))


;; ----------------------------------------------------------------
;; to-last-child! with name param goes to the last child with the
;; specified name
;; ----------------------------------------------------------------
(deftest to-last-child-name-test
  (let [organizations (find-indexes nav
                                    "/Directory/Organizations/Organization")
        org1 (first organizations)
        org2 (nth organizations 1)]
   (is (to-last-child! org1 "Employee")) ;; move to last Employee
   (is (= "Willie Williams" (first-match org1 "Name")))
   (is (to-last-child! org2 "Location"))
   (is (= "US Capitol" (first-match org2 ".")))))


;; ----------------------------------------------------------------
;; to-next-sibling! goes to the correct sibling (if no name param,
;; should go to next sibling; if name param exists, should go to
;; next element with that name).
;; ----------------------------------------------------------------
(deftest to-next-sibling-test
 (let [nav-obj (first (find-indexes
                       nav "/Directory/Organizations/Organization/Location"))]
   (is (to-next-sibling! nav-obj))
   (is (= "Employee" (node-name nav-obj)))
   (is (to-next-sibling! nav-obj "ApprovalRating"))
   (is (= "ApprovalRating" (node-name nav-obj)))))


;; ----------------------------------------------------------------
;; to-next-sibling! returns false if there is no next sibling
;; or if there is no next sibling with the specified name
;; ----------------------------------------------------------------
(deftest to-next-sibling-not-found-test
  (let [organizations (find-indexes nav
                                    "/Directory/Organizations/Organization")
        org-first (first organizations)
        org-last (last organizations)]
    (is (not (to-next-sibling! org-last)))
    (is (not (to-next-sibling! org-first "IDontExist")))))


;; ----------------------------------------------------------------
;; to-prev-sibling! goes to the correct sibling (if no name param,
;; should go to next sibling; if name param exists, should go to
;; next element with that name).
;; ----------------------------------------------------------------
(deftest to-prev-sibling-test
  (let [employee (first (find-indexes
                         nav
                         "/Directory/Organizations/Organization/Employee"))
        approval (first
                  (find-indexes
                   nav
                   "/Directory/Organizations/Organization/ApprovalRating"))]
   (is (to-prev-sibling! employee))
   (is (= "Location" (node-name employee)))
   (is (to-prev-sibling! approval "Location"))
   (is (= "Location" (node-name approval)))))



;; ----------------------------------------------------------------
;; to-prev-sibling! returns false if there is no previous sibling
;; or if there is no previous sibling with the specified name
;; ----------------------------------------------------------------
(deftest to-prev-sibling-no-match-test
  (let [org1 (first (find-indexes nav
                                  "//Directory/Organizations/Organization"))]
    (is (not (to-prev-sibling! org1)))
    (is (not (to-prev-sibling! org1 "IDontExist")))))



;; ----------------------------------------------------------------
;; --------------- NAMESPACE TESTS FROM HERE DOWN -----------------
;; ----------------------------------------------------------------



;; ----------------------------------------------------------------
;; Make sure that find-indexes works with XPATH namespaces.
;; Pass in an optional namespace hash.
;; ----------------------------------------------------------------
(deftest find-indexes-ns-test
  (is (= 4 (count (find-indexes
                   nav-ns
                   "/Directory/ns1:Organizations/ns1:Organization"
                   ns-hash))))
  (is (= 5 (count (find-indexes
                   nav-ns
                   (str "/Directory/ns1:Organizations"
                        "/ns1:Organization"
                        "/ns2:Employee[@role='Elected Member']")
                   ns-hash))))
  (is (= 12 (count (find-indexes nav-ns "//Name" ns-hash))))

  ;; Get a VTDNav object pointing to the first organization element.
  ;; When we run relative XPATH queries on this, it should return only
  ;; the self or descendants of this element.
  (let [first-org (first
                   (find-indexes
                    nav-ns
                    "/Directory/ns1:Organizations/ns1:Organization"
                    ns-hash))]
    (is (= 3 (count (find-indexes first-org "ns2:Employee" ns-hash))))
    (is (= 3 (count (find-indexes first-org "ns2:Employee/Name" ns-hash))))
    (is (= 3 (count (find-indexes first-org "ns2:Employee//Name" ns-hash))))))


;; ----------------------------------------------------------------
;; List first-match-test above, but with namespaces
;; ----------------------------------------------------------------
(deftest first-match-ns-test
  (let [organizations (find-indexes
                       nav-ns
                       "/Directory/ns1:Organizations/ns1:Organization"
                       ns-hash)
       org1 (first organizations)
       org2 (nth organizations 1)]
   (is (= "John Boehner" (first-match org1 "ns2:Employee/Name" ns-hash)))
   (is (= "Willie Williams" (first-match org1
                                         "ns2:Employee[@role='Staff']/Name"
                                         ns-hash)))
   (is (= "John McCain" (first-match org2 "ns2:Employee/Name" ns-hash)))
   (is (= "Cassandra Hadoop" (first-match org2
                                          "ns2:Employee[@role='Staff']/Name"
                                          ns-hash)))))

;; ----------------------------------------------------------------
;; The all-matches test is described above. This is the same test
;; with namespaces.
;; ----------------------------------------------------------------
(deftest all-matches-ns-test
  (is (= 12 (count (all-matches nav-ns "//Name" ns-hash))))
  (is (= "John Boehner" (first (all-matches nav-ns
                                            "//ns2:Employee//Name"
                                            ns-hash))))
  (is (= "Lady Gaga" (last (all-matches nav-ns
                                        "//ns2:Employee//Name"
                                        ns-hash))))
  ;; Run all-matches on elements within a single element.
  ;; In this case, the last organization element.
  (let [president (last
                   (find-indexes
                    nav-ns
                    "/Directory/ns1:Organizations/ns1:Organization"
                    ns-hash))]
    (is (= 3 (count (all-matches president "ns2:Employee/Name"))))
    (is (= "Barack Obama" (first (all-matches
                                  president
                                  "ns2:Employee[@role='President']/Name"))))
    (is (= "Lady Gaga" (last (all-matches president "ns2:Employee/Name"))))))


;; ----------------------------------------------------------------
;; get-xml returns a seq containing the literal XML of all matching
;; nodes. Make sure this works with namespaces
;; ----------------------------------------------------------------
(deftest get-xml-ns-test
  (is (= "<ns2:Employee role=\"Elected Member\">
        <Name>John McCain</Name>
      </ns2:Employee>"
         (first (get-xml nav-ns senate-xpath-ns ns-hash))))
  (is (= 12 (count (get-xml nav-ns "//ns2:Employee" ns-hash)))))


;; ----------------------------------------------------------------
;; get-hash is described above. This tests to ensure it works with
;; namespaces
;; ----------------------------------------------------------------
(deftest get-hash-ns-test
 (is (= (first (get-hash nav-ns senate-xpath-ns ns-hash))
        {:tag   :ns2:Employee,
         :attrs {:role "Elected Member"},
         :content [{:tag :Name,
                    :attrs nil,
                    :content ["John McCain"]}]}))
  (is (= 12 (count (get-hash nav-ns "//ns2:Employee" ns-hash)))))
