# clojure-miniprofiler

A simple but effective profiler for clojure web applications.

clojure-miniprofiler displays a speed badge for every html page. It's designed
to work both in production and in development.

![](screenshot.png)

It can measure both server and client side timings.

## Installation

Miniprofiler can be installed [from clojars](https://clojars.org/clojure-miniprofiler)

## Usage

clojure-miniprofiler presents itself as a Ring middleware. It wraps requests
for html pages, and adds a timing badge to them. It can break down timings in
sections of your code to show you what parts of your app are slow.

The middleware is initialized by wrapping your app in
`clojure-miniprofiler/wrap-miniprofiler`.

### Access Control In Production

clojure-miniprofiler is designed with production usage in mind. To enable that, pass
an `:authorized?` callback to `wrap-miniprofiler`:

```clojure
(wrap-miniprofiler my-handler
  {:authorized? (fn [req] (user/admin? req))}
```

The authorized callback takes a request and returns a boolean value to say if
the profiler should be enabled in production. By default all requests from
`localhost` are profiled.

### Timing Your Code

clojure-miniprofiler supports two kinds of timing:
 - `trace`, which lets you demarkate a section of your code's execution (which turns into a tree of calls when you nest it). Trace is designed for applications where the request is handled by different kinds of code, e.g. in an rails-like "MVC" kinda architecture, you might split it up into `controller` and `view` (model calls typically happen in both of those).
- `custom-timing`, which demarkates a `call` of some kind - typically a
  database request, a cache lookup, a call to html rendering etc.

#### Trace

```clojure
(trace "view"
  my-view-code-here)
```

`trace` is very simple - it lets you create a tree so you can see when calls were made.

#### Custom-Timing

```clojure
(custom-timing "sql" "query" my-query-string
  (execute-sql-query my-query))
```

`custom-timing` is a bit more complicated. It represents calls inside your
code, to external services, to expensive pieces of application code, etc.

It takes 3 arguments in addition to the body of code to execute:
##### call-type:
the type of timed call this is. Examples might be \"sql\" or \"redis\"

##### execute-type:
within the call-type, what kind of request this is.
Examples might be \"get\" or \"query\" or \"execute\"

##### command-string:
a pretty printed string of what this is executing.
For SQL, this would be the query, for datomic the query or
transaction data, for redis the key you're getting etc.

### Storage

clojure-miniprofiler stores the results of timing requests in order to render
them on the page - this means you can link to profiling results and share them with
other people working on your app, refer back to them later etc.

To do this, clojure-miniprofiler needs a storage implementation that works for
your environment. The default storage is in-memory only, which will not work
well for production environments, which are typically multi-machine and
multi-process. However, it works very well for development.

To provide your own storage (which is best backed by a database of some kind),
you have to implement the protocol in `clojure-miniprofiler/store`:

```clojure
(defprotocol Storage
  "Storage is how you create custom storage for your
   miniprofiler results. In production, you'll want to use
   a persistent data store that is shared between multiple threads,
   with your database being a good example. Key-Value stores such as
   redis, riak etc are also especially suited to miniprofiler storage,
   as they're fast and the interface required is very small."
  (save [this profile]
        "save takes a profile (which is effectively an arbitrary map),
         and stores it in storage. Note that the map isn't serialized, you'll
         have to do that logic yourself.
        The key the map should be stored under can be found in it as \"Id\"")
  (fetch [this id]
         "fetch takes the Id asked for and returns the deserialized result from
          the specified session."))
```

`Id` are just string representations of UUIDs, and the profiles to be saved are
just maps with strings and numbers in them. It should be fairly easy to
implement that interface using any key-value store, or any other kind of
database.

## License

Copyright © 2014 tcrayford

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
