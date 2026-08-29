ملاحظة: ملف gradle-wrapper.jar غير موجود في هذا المجلد.

تم تعديل GitHub Actions workflow (.github/workflows/build-apk.yml) بحيث يقوم
بتنزيل وتثبيت Gradle 8.4 مباشرة على السيرفر بدلاً من استخدام "./gradlew"،
لذلك البناء على GitHub سيعمل بشكل طبيعي بدون الحاجة لهذا الملف.

هذا الملف مطلوب فقط إذا أردت تشغيل الأمر "./gradlew" على جهازك الشخصي
(مثلاً داخل Android Studio أو Terminal محلي). إذا لم تكن تخطط لذلك،
يمكنك تجاهل هذا الملف تماماً.

لإصلاحه لاحقاً عند الحاجة:
1) لو عندك Gradle مثبت محلياً: gradle wrapper --gradle-version 8.4
2) أو افتح المشروع في Android Studio (يولّد الـ wrapper تلقائياً).
3) أو نزّله من:
   https://raw.githubusercontent.com/gradle/gradle/v8.4.0/gradle/wrapper/gradle-wrapper.jar
