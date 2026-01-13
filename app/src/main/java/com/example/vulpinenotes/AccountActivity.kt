package com.example.vulpinenotes

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.bumptech.glide.Glide
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import de.hdodenhof.circleimageview.CircleImageView
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

class AccountActivity : BaseActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient

    private lateinit var profileImage: CircleImageView
    private lateinit var textName: TextView
    private lateinit var textEmail: TextView
    private lateinit var btnSignIn: Button
    private lateinit var btnSignOut: Button

    // Константа для кода запроса входа через Google
    private val RC_SIGN_IN = 9001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_account)

        // Настройка Toolbar с кнопкой "Назад" и заголовком
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.account_title)

        // Инициализация Firebase Authentication
        auth = FirebaseAuth.getInstance()

        // Конфигурация Google Sign-In с запросом токена ID и email
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        // Привязка элементов UI к переменным
        profileImage = findViewById(R.id.profile_image)
        textName = findViewById(R.id.text_name)
        textEmail = findViewById(R.id.text_email)
        btnSignIn = findViewById(R.id.btn_sign_in)
        btnSignOut = findViewById(R.id.btn_sign_out)

        // Назначение обработчиков кликов для кнопок входа/выхода
        btnSignIn.setOnClickListener { signIn() }
        btnSignOut.setOnClickListener { signOut() }

        // Обновление UI в соответствии с текущим состоянием пользователя
        updateUI(auth.currentUser)
    }

    // Запуск процесса входа через Google
    private fun signIn() {
        val signInIntent = googleSignInClient.signInIntent
        startActivityForResult(signInIntent, RC_SIGN_IN)
    }

    // Выход из аккаунта с очисткой данных и завершением активности
    private fun signOut() {
        // Выход из Firebase и Google аккаунтов
        auth.signOut()
        googleSignInClient.signOut()

        // Очистка кэша Glide для изображений профиля
        Glide.get(this@AccountActivity).clearMemory()

        // Установка результата для родительской активности (Из мейн активити для обновления UI)
        setResult(RESULT_OK)
        finish()
    }

    // Обработка результата активности Google Sign-In
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        // Проверка, что результат пришел от Google Sign-In
        if (requestCode == RC_SIGN_IN) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                // Получение данных аккаунта Google и аутентификация в Firebase
                val account = task.getResult(ApiException::class.java)
                firebaseAuthWithGoogle(account.idToken!!)
            } catch (e: ApiException) {
                // Обработка ошибки входа через Google
                Toast.makeText(this, "Вход не удался", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Аутентификация в Firebase с использованием Google токена
    private fun firebaseAuthWithGoogle(idToken: String) {
        // Создание учетных данных Firebase из Google токена
        val credential = GoogleAuthProvider.getCredential(idToken, null)

        // Здесь потом добавлю индикатор загрузки в аккаунте
        // findViewById<ProgressBar>(R.id.progressBar)?.visibility = View.VISIBLE
        // btnSignIn.isEnabled = false

        // Выполнение входа с учетными данными Google
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                // Скрытие индикатора загрузки (если будет добавлен)
                // findViewById<ProgressBar>(R.id.progressBar)?.visibility = View.GONE
                // btnSignIn.isEnabled = true

                if (task.isSuccessful) {
                    // Успешный вход: сохранение пользователя и обновление UI
                    val user = auth.currentUser
                    if (user != null) {
                        saveUserToFirestore(user)  // Сохранение в Firestore
                        updateUI(user)             // Обновление интерфейса
                        setResult(RESULT_OK)       // Установка результата
                        finish()
                    }
                } else {
                    // Обработка ошибки аутентификации
                    Toast.makeText(this, "Ошибка авторизации: ${task.exception?.localizedMessage}", Toast.LENGTH_LONG).show()
                    updateUI(null)  // Обновление UI для незалогиненного состояния
                }
            }
    }

    // Сохранение данных пользователя в Firestore
    private fun saveUserToFirestore(user: FirebaseUser) {
        // Подготовка данных пользователя для сохранения
        val userData = mapOf(
            "name"     to (user.displayName ?: ""),
            "email"    to (user.email ?: ""),
            "photoUrl" to (user.photoUrl?.toString() ?: "")
        )

        // Сохранение в коллекции "users" с объединением существующих данных
        FirebaseFirestore.getInstance()
            .collection("users")
            .document(user.uid)  // UID пользователя как ID документа
            .set(userData, SetOptions.merge())
        // .addOnFailureListener { ... }
    }

    // Обновление UI в зависимости от состояния авторизации
    private fun updateUI(user: FirebaseUser?) {
        if (user != null) {
            // Отображение данных авторизованного пользователя
            textName.text = user.displayName ?: "Неизвестно"
            textEmail.text = user.email ?: ""
            Glide.with(this).load(user.photoUrl).into(profileImage)  // Загрузка фото
            btnSignIn.visibility = View.GONE     // Скрыть кнопку входа
            btnSignOut.visibility = View.VISIBLE // Показать кнопку выхода
        } else {
            // Отображение состояния "не авторизован"
            textName.text = "Вы не авторизованы"
            textEmail.text = ""
            profileImage.setImageResource(R.drawable.ic_fox_logo)  // Логотип по умолчанию
            btnSignIn.visibility = View.VISIBLE  // Показать кнопку входа
            btnSignOut.visibility = View.GONE    // Скрыть кнопку выхода
        }
    }

    // Обработка нажатия кнопки "Назад" в Toolbar
    override fun onSupportNavigateUp(): Boolean {
        finish()  // Завершение текущей активности
        return true
    }
}