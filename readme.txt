INTRODUCTION:

    I always wanted something that wrapped HTTP 
    in Java without forcing a ton of crap on me 
    like JSP and a million other things.
    
    I think the HTTP server should be small and 
    easy to use without sacrificing performance 
    or basic features.

RUN:

    You need java 1.4 or later installed and 
    added to the path and JAVA_HOME set to run 
    the server.

    >run.sh OR
    >run.bat

BUILD AND TEST OR DOCUMENT:

    You need ant 1.7.0 installed and added 
    to the path and ANT_HOME set to build the 
    server.

    >ant OR
    >ant doc

APPLICATION REMOTE HOT DEPLOYMENT:

    Implement se.rupy.http.Service and deploy 
    the jar containing your application like this:

    <target name="deploy">
        <java fork="yes" 
              classname="se.rupy.http.Deploy" 
              classpath="http.jar">
            <arg line="localhost:8000"/><!-- any host:port -->
            <arg line="service.jar"/><!-- your application jar -->
            <arg line="secret"/><!-- see run.bat and run.sh -->
        </java>
    </target>

VERSION:

    0.1 - Alpha

    - Asynchronous response.
    - Added OP_WRITE so that the server can send 
      large amounts of data.
    - Finished and tested chunked transfer encoding.
    - Session timeout / TCP disconnect feedback to 
      visited services.
    - 302 Found.
    - 304 Not Modified.
    - 404 Not Found.
    - 500 Internal Server Error.
    - Static content to disk.
    
    0.2 - Beta
    
    - Fixed a ton of bugs and refactored most classes.
    - Added multipathed services, so that you can deploy 
      one service at the same index in multiple chains 
      without having to write separate services.
    - Added javadoc ant task.
    - Queue events when all workers are busy to avoid 
      selector thrashing.

      0.2.1

      - Fixes an extremely rare but fatal bug which left 
        the server throttling at 99% CPU.
      - Also includes some helper method additions and 
        re-factorings to Hash.
      - Daemon now takes Properties, so you can use a 
        properties text file!
      - Probably some other small things here and there.
      
      0.2.2

      - Refactored the deployment of archives completely.
      - Fixed a couple of bugs with the query parameters.

have fun!