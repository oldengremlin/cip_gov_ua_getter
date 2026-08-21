/*
 * Copyright 2025 olden
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
package net.ukrcom.cip_gov_ua_getter;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Атомарний запис у файл: спершу тимчасовий файл, потім перейменування.
 * <p>
 * Обірваний запис ніколи не має лишити частковий файл на місці цільового —
 * інакше пошкоджена копія осідає в кеші й використовується як справжня
 * (перевірки на кшталт {@code isExists()} бачать її як валідну). Логіка
 * зібрана тут, щоб не дублюватися по всіх місцях, де ми щось зберігаємо.
 *
 * @author olden
 */
public final class AtomicFiles {

    private AtomicFiles() {
    }

    /**
     * Повертає шлях тимчасового файлу поруч із цільовим.
     *
     * @param target цільовий файл
     * @return шлях тимчасового файлу
     */
    public static Path tempFor(Path target) {
        return target.resolveSibling(target.getFileName() + ".tmp");
    }

    /**
     * Атомарно записує вміст у цільовий файл через тимчасовий.
     *
     * @param target цільовий файл
     * @param content вміст
     * @throws IOException у разі помилки запису
     */
    public static void write(Path target, byte[] content) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = tempFor(target);
        try {
            Files.write(temp, content);
            moveIntoPlace(temp, target);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    /**
     * Переміщує вже готовий тимчасовий файл на місце цільового.
     * <p>
     * {@code ATOMIC_MOVE} і {@code REPLACE_EXISTING} передаються разом: без
     * другого прапорця заміна вже наявного файлу впала б із
     * {@code FileAlreadyExistsException}. Якщо файлова система не підтримує
     * атомарного переміщення — відкочуємось на звичайне.
     *
     * @param temp тимчасовий файл із готовим вмістом
     * @param target цільовий файл
     * @throws IOException у разі помилки переміщення
     */
    public static void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
