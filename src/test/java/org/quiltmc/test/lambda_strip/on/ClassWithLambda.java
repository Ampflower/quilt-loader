/*
 * Copyright 2022 QuiltMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.quiltmc.test.lambda_strip.on;

import org.quiltmc.loader.api.minecraft.ClientOnly;

public class ClassWithLambda implements @ClientOnly ClientItf {

	@ClientOnly
	public static void sayHello() {
		run(() -> System.out.println("Hello"));
	}

	public static void run(Runnable task) {
		task.run();
	}

	@Override
	@ClientOnly
	public void sayHi() {
		sayHello();
	}
}
