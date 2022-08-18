/*
 * Copyright (c) 2018. Manuel D. Rossetti, rossetti@uark.edu
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package ksl.utilities.io

import java.io.File
import java.io.OutputStream
import java.io.PrintWriter
import java.io.Writer
import java.util.*

/** A wrapper for a PrintWriter.  This class has all the functionality of
 * PrintWriter but has a public property OUTPUT_ON that can be set to false
 * to turn off any printing or set to true to turn printing on.
 * @author rossetti
 */
class LogPrintWriter : PrintWriter {
    /**
     * Controls whether or not any the PrintWriter functionality happens
     */
    var OUTPUT_ON = true

    /**
     * @param out the Writer
     */
    constructor(out: Writer) : super(out) {}

    /**
     * @param out the output stream
     */
    constructor(out: OutputStream) : super(out) {}

    /**
     * @param fileName the file name
     * @throws FileNotFoundException the exception
     */
    constructor(fileName: String) : super(fileName) {}

    /**
     * @param file the file
     * @throws FileNotFoundException the exception
     */
    constructor(file: File) : super(file) {}

    /**
     * @param out the Writer
     * @param autoFlush true means auto flush
     */
    constructor(out: Writer, autoFlush: Boolean) : super(out, autoFlush) {}

    /**
     * @param out the Writer
     * @param autoFlush true means auto flush
     */
    constructor(out: OutputStream, autoFlush: Boolean) : super(out, autoFlush) {}

    /**
     * @param fileName the file name to use
     * @param csn name of the character set
     * @throws FileNotFoundException an exception
     * @throws UnsupportedEncodingException an exception
     */
    constructor(fileName: String, csn: String) : super(fileName, csn) {}

    /**
     * @param file the file
     * @param csn name of the character set
     * @throws FileNotFoundException an exception
     * @throws UnsupportedEncodingException an exception
     */
    constructor(file: File, csn: String) : super(file, csn) {}

    override fun println(x: String) {
        if (OUTPUT_ON) {
            super.println(x)
        }
    }

    override fun append(c: Char): PrintWriter {
        return if (OUTPUT_ON) {
            super.append(c)
        } else {
            this
        }
    }

    override fun append(csq: CharSequence, start: Int, end: Int): PrintWriter {
        return if (OUTPUT_ON) {
            super.append(csq, start, end)
        } else {
            this
        }
    }

    override fun append(csq: CharSequence): PrintWriter {
        return if (OUTPUT_ON) {
            super.append(csq)
        } else {
            this
        }
    }

    override fun print(b: Boolean) {
        if (OUTPUT_ON) {
            super.print(b)
        }
    }

    override fun print(c: Char) {
        if (OUTPUT_ON) {
            super.print(c)
        }
    }

    override fun print(s: CharArray) {
        if (OUTPUT_ON) {
            super.print(s)
        }
    }

    override fun print(d: Double) {
        if (OUTPUT_ON) {
            super.print(d)
        }
    }

    override fun print(f: Float) {
        if (OUTPUT_ON) {
            super.print(f)
        }
    }

    override fun print(i: Int) {
        if (OUTPUT_ON) {
            super.print(i)
        }
    }

    override fun print(l: Long) {
        if (OUTPUT_ON) {
            super.print(l)
        }
    }

    override fun print(obj: Any) {
        if (OUTPUT_ON) {
            super.print(obj)
        }
    }

    override fun print(s: String) {
        if (OUTPUT_ON) {
            super.print(s)
        }
    }

    override fun printf(l: Locale, format: String, vararg args: Any): PrintWriter {
        return if (OUTPUT_ON) {
            super.printf(l, format, *args)
        } else {
            this
        }
    }

    override fun printf(format: String, vararg args: Any): PrintWriter {
        return if (OUTPUT_ON) {
            super.printf(format, *args)
        } else {
            this
        }
    }

    override fun println() {
        if (OUTPUT_ON) {
            super.println()
        }
    }

    override fun println(x: Boolean) {
        if (OUTPUT_ON) {
            super.println(x)
        }
    }

    override fun println(x: Char) {
        if (OUTPUT_ON) {
            super.println(x)
        }
    }

    override fun println(x: CharArray) {
        if (OUTPUT_ON) {
            super.println(x)
        }
    }

    override fun println(x: Double) {
        if (OUTPUT_ON) {
            super.println(x)
        }
    }

    override fun println(x: Float) {
        if (OUTPUT_ON) {
            super.println(x)
        }
    }

    override fun println(x: Int) {
        if (OUTPUT_ON) {
            super.println(x)
        }
    }

    override fun println(x: Long) {
        if (OUTPUT_ON) {
            super.println(x)
        }
    }

    override fun println(x: Any) {
        if (OUTPUT_ON) {
            super.println(x)
        }
    }

    override fun write(buf: CharArray, off: Int, len: Int) {
        if (OUTPUT_ON) {
            super.write(buf, off, len)
        }
    }

    override fun write(buf: CharArray) {
        if (OUTPUT_ON) {
            super.write(buf)
        }
    }

    override fun write(c: Int) {
        if (OUTPUT_ON) {
            super.write(c)
        }
    }

    override fun write(s: String, off: Int, len: Int) {
        if (OUTPUT_ON) {
            super.write(s, off, len)
        }
    }

    override fun write(s: String) {
        if (OUTPUT_ON) {
            super.write(s)
        }
    }
}