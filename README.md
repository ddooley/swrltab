SWRLTab
=======

[![Build Status](https://travis-ci.org/protegeproject/swrltab.svg?branch=master)](https://travis-ci.org/protegeproject/swrltab)

The SWRLTab is a [SWRLAPI](https://github.com/protegeproject/swrlapi/wiki)-based environment that provides a set of standalone graphical interfaces for managing SWRL rules and SQWRL queries. 

Documentation can be found at the [SWRLTab Wiki](https://github.com/protegeproject/swrltab/wiki).

A [Protégé Desktop Ontology Editor](http://protege.stanford.edu)-based [SWRLTab Plugin](https://github.com/protegeproject/swrltab-plugin/wiki) is also available.

### Building and Running

To build and run this project you must have the following items installed:

+ [Java 11](http://www.oracle.com/technetwork/java/javase/downloads/index.html) or later
+ A tool for checking out a [Git](http://git-scm.com/) repository
+ Apache's [Maven](http://maven.apache.org/index.html)

Get a copy of the latest code:

    git clone https://github.com/protegeproject/swrltab.git 

Change into the swrltab directory:

    cd swrltab

Build it with Maven:

    mvn clean install

On build completion, your local Maven repository will contain generated swrltab-${version}.jar and swrltab-${version}-jar-with-dependencies.jar files.
The ./target directory will also contain these JARs.

You can then run the standalone SQWRLTab as follows:

    mvn exec:java

You can run the standalone SWRLTab as follows:

    java -cp ./target/swrltab-${version}-jar-with-dependencies.jar org.swrltab.ui.SWRLTab 

### Headless CLI (SWRLTabCLI)

SWRLTabCLI is a headless command-line add-on for running SWRL inference and SQWRL queries without a GUI, developed by Damion Dooley at the [Centre for Infectious Disease Genomics and One Health (CIDGOH)](https://cidgoh.ca) at Simon Fraser University. The SWRLTabCLI code was generated with [Claude Code](https://claude.ai/code).

Build the fat JAR first (only needed once, or after code changes):

    mvn clean package

A wrapper script is provided for convenience:

    ./swrltabcli [options] <ontology-file>

Full documentation, including all options, examples, and the `.swrl` file format, is in [README_swrltabcli.md](README_swrltabcli.md).

#### License

The software is licensed under the [BSD 2-clause License](https://github.com/protegeproject/swrltab/blob/master/license.txt).

#### Questions

If you have questions about this library, please go to the main
Protégé website and subscribe to the [Protégé Developer Support
mailing list](http://protege.stanford.edu/support.php#mailingListSupport).
After subscribing, send messages to protege-dev at lists.stanford.edu.
