# clj-vtd-xml: A Simple Clojure VTD-XML Library

clj-vtd-xml is a Clojure wrapper around the Ximpleware VTD-XML library. It has
no dependencies other than Clojure itself and the Ximpleware VTD-XML jar.

The VTD parser is very fast-- up to 10 times as fast as the standard Java DOM
parser, and is also considerably faster than most SAX parsers. It's useful in
systems that must quickly process large volumes of XML data.

The VTD parser works quite differently from both DOM and SAX, and its native
Java API requires some learning. This wrapper provides a simple, intuitive API
to spare you the learning curve and to make common operations easy.

Although VTD can be used to alter and write XML documents, this library focuses
only on parsing and reading XML.

## Building and Testing

<pre>
$ lein deps
$ lein test
$ lein jar
</pre>

## Usage

For examples of how to use this library, please see the tests in
clj-vtd-xml/test/clj_vtd_xml/test/core.clj, which are heavily commented.

Here's a simple example that extracts text from a document that contains no
namespaces.

```clojure

    (ns my-parser
        (:use [clj-vtd-xml.core]))

    ;; Assume this string contains the entire text of your XML document.
    (def xml "<xml><root>...</root>")

    ;; Create the navigator without namespace support (last param = false).
    ;; The navigator contains a parsed VTD representation of the XML.
    (def nav (navigator xml false))

    ;; Extract the city name at the given XPATH. Returns string.
    (defn get-city []
        (first-match nav "/Address/Address/City"))

    ;; Extract all of the State names from the XML document.
    ;; Returns a seq of strings.
    (defn get-all-states []
        (all-matches nav "//State"))

    ;; Extract the raw XML of all elements matching XPATH.
    ;; Returns a seq of strings, each containing the literal XML
    ;; of an Address element.
    (defn get-address-xml []
        (get-xml nav "//Address"))

    ;; Convert all the Address elements into a seq hashes.
    ;; See the test code for an example of what these hashes look like.
    (defn get-address-hashes []
        (get-hash nav "//Address"))

```

To parse an XML document with namespace support, you must create the initial
navigator object with the last param set to true:

```clojure

    ;; Create the navigator with namespace support (last param = true).
    (def nav-ns (navigator xml true))

    ;; Define a hash that maps namespace prefixes to their URLs
    (def ns-hash {"ns1" "http://www.skeema.kom/ns1"
                  "ns2" "http://www.skeema.kom/ns2"})

    ;; All calls to first-match, all-matches, get-xml, and get-hash
    ;; are the same as above, except that you pass in a namespace-aware
    ;; navigator object and ns-hash as the final parameter.
    (defn get-city []
        (first-match nav-ns "/Address/Address/City" ns-hash))

    (defn get-all-states []
        (all-matches nav-ns "//State" ns-hash))

    (defn get-address-xml []
        (get-xml nav-ns "//Address" ns-hash))

    (defn get-address-hashes []
        (get-hash nav-ns "//Address" ns-hash))

```

In addition to these XPATH functions, the library also exposes the standard
VTD navigation methods for moving to parent, child, and sibling elements.
Note that those functions affect the internal state of the VTDNav object.
This is described in more detail under "Understanding VTD-XML" below.

This library always evaluates relative XPATH expressions relative to the
current position of the navigator object. For example, if you want to get
all of the address elements within a document that have the attribute
Type = 'Residence', and then extract the city name from each of those elements,
you can do this:

```clojure

    (defn get-cities-of-residence []
        (let [residence-addresses
              (find-indexes nav "//Address[@Type = 'Residence']")]
            (map #(first-match % "City") residence-addresses)))

```

Of course, you could do that with a single XPATH expression, but the code
above illustrates how you would go about isolating individual elements to
be processed one at a time.

See the tests for examples and additional details.

## Limitations

* The library supports only XML reading. It will not write or alter XML.
* The functions first-match and all-matches work only with XPATH expressions
  that point to nodes containing nothing but text. That is, attributes or
  elements that contain text <SuchAs>This</SuchAs>. If you want to extract
  elements that contain other elements, use find-indexes, get-xml, get-hash,
  or raw-xml-stream.
* The functions first-match and all-matches return values as strings. If
  you want numbers, booleans, or other types, you will have to convert them
  yourself.
* This wrapper uses VTDGen, not VTDGenHuge, so it can parse XML documents
  up to about 4GB in size.

## Common Exceptions

To avoid this exception:

&gt; com.ximpleware.XPathParseException: No URL found for prefix:xyz

Be sure to create the initial navigator object with namespace support (that is,
with the last param set to true), and to pass in the namespace hash as the
final param to find-indexes, first-match, all-matches, get-xml, and get-hash.

## Understanding VTD-XML

This section explains how the underlying VTD-XML Java library works. You don't
need to read this section to use the Clojure library, but it may help you
understand what's going on beneath the surface.

The VTD parser does not create a DOM object, nor does it generate SAX events.
It reads XML into a byte stream, and creates an index describing the
offset and length within the byte stream of every element in the XML document.
This process is considerably faster than DOM parsing, and uses much less
memory, since the parser never builds a DOM tree.

Once the XML is parsed into byte stream and index, the VTDNav object provides
a means of accessing elements within the document. The VTDNav object contains
a pointer into the XML byte stream and methods to move that pointer to
different elements.

Internally, these methods use the VTD index to find elements within the byte
stream by their offset. The to- functions in this Clojure wrapper (to-parent!,
to-first-child!, etc.) call methods on the VTD object that move the internal
pointer to specified element in the same way that C's fseek function moves
a pointer through a file stream.

Moving the pointer changes the internal state of the VTDNav object. You'll
notice this if you call (first-match nav "."), then call
(to-next-sibling! nav), then call (first-match nav ".") again. The second
call to (first-match nav ".") will return something different from what
the first call returned.

This is unexpected behavior for Clojure, which is typically stateless, but
this is the nature of the VTD parser.

XPATH searches also alter the position of the VTDNav object's internal pointer.
Because of this, the clj-vtd-xml library clones the VTDNav object every time
you do an XPATH search. This is a fairly lightweight operation. Cloning a
VTDNav object creates a new VTDNav pointing to the same position in the byte
stream as the original. It does not copy the XML byte stream itself or the
index. All of the clones share a single instance of the byte stream and index.
Cloning assures that the navigator you pass in to a function will not change
during the function's execution.

One final note about VTD is that it indexes only elements. When you call
find-indexes on an XPATH expression that points to an attribute, you'll
actually get the index of the element that contains that attribute. For
example, using the sample.xml document in the test directory, the following
function prints 237 for both pos1 and pos2:

```clojure

    (let [nav1 (first (find-indexes nav "//Employee[position() = 1]"))
          nav2 (first (find-indexes nav "//Employee[position() = 1]/@role"))
          pos1 (position nav1)
          pos2 (position nav2)]
      (println (str "nav1 points to offset " (:offset pos1)))
      (println (str "nav2 points to offset " (:offset pos2))))

```

You don't need to worry about this when you call first-match or all-matches.
Those functions will yield the text you expect.

But if you're digging down into the innards of the VTD parser, it may save
you some confusion to know that it indexes only elements.

## License

Copyright (c) 2013 A. Diamond

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
