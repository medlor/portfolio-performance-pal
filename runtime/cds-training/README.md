# AppCDS training inputs

`tools/build-runtime` runs a few representative commands on these files while it
builds the class-data-sharing archive (`app.jsa`) for a runtime. That archive
only speeds up JVM start-up; it never changes behaviour. The files are copies of
the invented fixtures `fixtures/v1/portfolio.xml` and `fixtures/v1/baader-buy.pdf`.
They contain no owner data.
