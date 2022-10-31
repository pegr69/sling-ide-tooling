 /*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sling.ide.test.impl;

import java.time.Instant;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import org.apache.sling.ide.eclipse.core.logger.LogSubscriber;
import org.osgi.service.component.annotations.Component;

/**
 * Emits all log messages to the standard output.
 */
@Component()
public class VerboseStandardLogger implements LogSubscriber {

	@Override
	public void log(Severity severity, String message, Throwable t) {
		// only some severity
		System.out.println("[" + Thread.currentThread().getName() + "] " + DateTimeFormatter.ISO_LOCAL_TIME.format(LocalTime.now())
                +" : " + message);
        if (t != null)
            t.printStackTrace(System.out);
	}
}
