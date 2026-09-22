package cn.smsmail.app
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
fun Modifier.semanticsLabel(label: String): Modifier = semantics { contentDescription = label }
